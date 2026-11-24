package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.DeliveryOutcome
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.feature.media.PageReaderClient
import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePageContext
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.PromptContext
import ai.opencode.server.retry.NetworkPathClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.RetrySchedule
import ai.opencode.server.retry.RetrySignals
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.retry.retryVerdict
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.TimeSource

/** A message whose send failed. Retry reuses its ID, which the server admits at most once. */
data class FailedSend(
    val pending: PendingPrompt,
    val reason: String,
) {
    val id: String get() = pending.messageID

    /**
     * The session's folder no longer exists on the computer, so every
     * retry fails the same way until the session moves somewhere that does.
     */
    val isFolderGone: Boolean get() = reason == OpenCodeClientError.FOLDER_GONE_MESSAGE || reason == moveFailedReason

    /** [reason] as the transcript shows it: the saved English [moveFailedReason] reads in the app's language. */
    val displayReason: String get() = if (reason == moveFailedReason) localized(R.string.session_move_failed_reason) else reason

    companion object {
        /** Saved with the send and compared in [isFolderGone], so it stays English; [displayReason] shows it. */
        const val moveFailedReason = "Couldn’t move session"
    }
}

/** Why a send hasn't reached the server yet, when the reader needs to know. */
enum class DeliveryNote {
    /** An earlier send failed, and this one waits behind it. */
    waiting;

    val label: String
        get() = when (this) {
            waiting -> localized(R.string.session_delivery_waiting_to_send)
        }
}

val List<FailedSend>.ids: List<String> get() = map { it.id }

fun List<FailedSend>.withID(id: String): FailedSend? = firstOrNull { it.id == id }

/**
 * Delivers one prompt, creating the session (and applying a pre-picked model) first unless an
 * earlier send in its lane already did. A retry first looks for the message by ID, in case the
 * failed attempt landed after all. Offline, a timeout, a 408/429/502/503/504 is tried again with
 * the send's backoff (a Retry-After respected), under the same IDs, which the server admits once.
 * This loop is the send's only retry layer: the client calls inside it are each sent once. Each
 * attempt posts the send as it stands then; what the reader asked while it was being posted
 * reaches the server once it's admitted.
 */
internal fun SessionFeature.Companion.deliveryJob(
    target: SessionFeature.SendTarget,
    server: OpenCodeServer,
    reconcileFirst: Boolean,
    openCodeClient: OpenCodeClient,
    clock: Clock,
    networkPath: NetworkPathClient,
    undeliveredPrompts: UndeliveredPrompts,
    pageReader: PageReaderClient,
): PromptOutbox.Job {
    suspend fun deliver(handle: PromptOutbox.Handle): DeliveryOutcome {
        // The send's backoff.
        val schedule = RetrySchedule(RetryPolicy.send, clock, networkPath.retryEnvironment)
        val log = AppLog.send
        val sendID = handle.current().messageID
        val started = TimeSource.Monotonic.markNow()
        var attempts = 0
        // Watched for the job's life, so one that comes mid-attempt counts.
        val signals = RetrySignals(networkPath, server.persistenceID, currentCoroutineContext())
        try {
            var atAttempt = signals.counts
            // Entering the background or coming back starts the window over.
            fun epoch(counts: RetrySignals.Counts): Int = counts.backgrounds + counts.activations
            var windowEpoch = epoch(atAttempt)
            var skippedLastWait = false
            fun attemptStarting() {
                handle.networkOperation.started()
                attempts += 1
                atAttempt = signals.counts
            }
            fun failedForGood(reason: String) {
                val tries = if (attempts == 1) "1 attempt" else "$attempts attempts"
                log.error { "$sendID failed after $tries in ${started.elapsedNow().logSummary}: $reason" }
            }

            // After Stop, an attempt that fails isn't tried again. A prompt and a session created with
            // a client ID are idempotent on the server. A wait after a connection failure ends as soon
            // as the server answers, the route changes, or the app becomes active; if the server
            // answered while the attempt was out, it goes again at once (never twice in a row, so a
            // server that answers reads but drops this request can't spin it). Time in the background
            // doesn't count: the window starts over when the app leaves and when it comes back. A wait
            // that runs out (offline past the window) ends the retries. Delete or Stop cut a wait short
            // too: the next `begin` says which.
            suspend fun waitToRetry(error: OpenCodeClientError): Boolean {
                if (handle.intent().stopped) {
                    log.notice { "$sendID attempt $attempts failed after Stop: ${error.logSummary}" }
                    return false
                }
                val verdict = error.retryVerdict(RequestIdempotency.Idempotent)
                val isConnectionFailure = error.transport != null
                while (true) {
                    if (epoch(signals.counts) != windowEpoch) {
                        windowEpoch = epoch(signals.counts)
                        schedule.reset()
                        log.info { "$sendID retry window starts over: the app left or came back" }
                    }
                    val seen = signals.counts
                    if (isConnectionFailure && !skippedLastWait && seen.reconnects != atAttempt.reconnects) {
                        if (!schedule.retryNow(verdict)) {
                            failedForGood("${error.logSummary} (${verdict.logSummary})")
                            return false
                        }
                        skippedLastWait = true
                        log.notice { "$sendID attempt $attempts failed: ${error.logSummary}; the server answered meanwhile, retrying now" }
                        handle.wait { true }
                        return true
                    }
                    val pause = schedule.next(verdict)
                    if (pause == null) {
                        failedForGood("${error.logSummary} (${verdict.logSummary})")
                        return false
                    }
                    skippedLastWait = false
                    log.notice { "$sendID attempt $attempts failed: ${error.logSummary}; ${pause.kind.logSummary}" }
                    val wake: (suspend () -> Unit)? = if (isConnectionFailure) {
                        { signals.next(onReconnect = true, since = seen) }
                    } else {
                        null
                    }
                    var expired = false
                    if (handle.wait {
                            val resumed = pause.run(wake)
                            if (!resumed && currentCoroutineContext().isActive) expired = true
                            resumed
                        }
                    ) {
                        return true
                    }
                    if (!expired) return true
                    // Offline past the window, unless the app left or came back during it: then it
                    // waits again with a fresh one.
                    if (epoch(signals.counts) == windowEpoch) {
                        failedForGood("still offline (${error.logSummary})")
                        return false
                    }
                }
            }

            // Kept until Retry, Edit, or Delete: if this screen is gone, reopening the session (or its
            // unsent draft from the session list) shows it again.
            fun failed(sessionID: String?, created: OpenCodeSession?, error: OpenCodeClientError): DeliveryOutcome {
                if (handle.intent().cancelled) return DeliveryOutcome.Withdrawn(sessionID, created)
                undeliveredPrompts.keep(handle.current(), error.message, server.persistenceID, sessionID ?: created?.id)
                return DeliveryOutcome.Failed(sessionID, created, error)
            }

            suspend fun moveBackgroundWorkIfNeeded(sessionID: String) {
                val key = handle.takeBackgroundKey() ?: return
                try {
                    openCodeClient.moveToBackground(server, sessionID)
                    handle.report(PromptOutbox.Status.BackgroundMoved(sessionID, key, null))
                } catch (error: OpenCodeClientError) {
                    handle.report(PromptOutbox.Status.BackgroundMoved(sessionID, key, error))
                }
            }

            // The agent this delivery switched the session to, for `Sent`.
            var agentApplied: String? = null

            // The server has it: what was asked meanwhile reaches it now. A change the server refused
            // leaves the send as the server has it: still there (a refused DELETE) or as it was
            // posted (a refused PATCH).
            suspend fun admissionOutcome(sessionID: String, created: OpenCodeSession?, appliedModel: OpenCodeModelRef?): DeliveryOutcome {
                val intent = handle.intent()
                val id = handle.current().messageID
                if (intent.cancelled) {
                    return try {
                        openCodeClient.cancelPendingInput(server, sessionID, id)
                        DeliveryOutcome.Withdrawn(sessionID, created)
                    } catch (error: OpenCodeClientError) {
                        handle.report(PromptOutbox.Status.UpdateFailed(sessionID, null, error))
                        DeliveryOutcome.Sent(sessionID, created, appliedModel, agentApplied)
                    }
                }
                intent.changedDelivery?.let { wanted ->
                    if (wanted == ai.opencode.server.model.OpenCodeDelivery.Steer) moveBackgroundWorkIfNeeded(sessionID)
                    try {
                        openCodeClient.setPendingInputDelivery(server, sessionID, id, wanted)
                    } catch (error: OpenCodeClientError) {
                        handle.report(PromptOutbox.Status.UpdateFailed(sessionID, intent.postedDelivery, error))
                    }
                }
                // Stopped while it was on its way, it's delivered all the same: Stop never interrupts
                // again on its own, which could end a run the reader started since.
                return DeliveryOutcome.Sent(sessionID, created, appliedModel, agentApplied)
            }

            // These are later user intents, not another attempt of the admitted send.
            suspend fun admitted(sessionID: String, created: OpenCodeSession?, appliedModel: OpenCodeModelRef? = null): DeliveryOutcome {
                PushSync.sentFromPhone(server, sessionID)
                return NetworkRetry.manualOperation.withValue(null) { admissionOutcome(sessionID, created, appliedModel) }
            }

            val sessionID: String
            var created: OpenCodeSession? = null
            val createdSessionID = handle.createdSessionID
            if (createdSessionID != null) {
                sessionID = createdSessionID
            } else {
                when (target) {
                    is SessionFeature.SendTarget.Existing -> sessionID = target.sessionID
                    is SessionFeature.SendTarget.NewSession -> {
                        creating@ while (true) {
                            when (handle.begin()) {
                                PromptOutbox.Attempt.Cancelled, PromptOutbox.Attempt.Superseded -> return DeliveryOutcome.Withdrawn(null, null)
                                PromptOutbox.Attempt.Stopped -> {
                                    log.info { "$sendID stopped before it was sent" }
                                    return failed(null, null, OpenCodeClientError(SessionFeature.stoppedReason))
                                }
                                is PromptOutbox.Attempt.Post -> Unit
                            }
                            attemptStarting()
                            log.info { "$sendID creating ${handle.newSessionID}, attempt $attempts" }
                            try {
                                val session = openCodeClient.createSession(server, target.directory, handle.newSessionID)
                                created = session
                                handle.created(session)
                                skippedLastWait = false
                                break@creating
                            } catch (error: OpenCodeClientError) {
                                if (!waitToRetry(error)) return failed(null, null, error)
                                handle.networkOperation.retried()
                            }
                        }
                        val session = created ?: return failed(null, null, OpenCodeClientError(localized(R.string.session_create_failed)))
                        sessionID = session.id
                    }
                }
            }
            val messageID = handle.current().messageID
            if (reconcileFirst && serverHas(messageID, sessionID, server, openCodeClient)) {
                // It landed after all, so nothing applied the model it carries (a pick made since it
                // failed, or the one it failed with): apply it now, once, as its post would have. A
                // failure leaves it waiting. Its agent too: a refusal leaves the pick waiting.
                val current = handle.current()
                val agent = current.agent
                if (agent != null && !current.waitsBehindRun &&
                    runCatching { openCodeClient.switchAgent(server, sessionID, agent) }.onFailure(::rethrowCancellation).isSuccess
                ) {
                    agentApplied = agent
                }
                var applied: OpenCodeModelRef? = null
                val model = current.model
                if (model != null && !current.waitsBehindRun &&
                    runCatching { openCodeClient.switchModel(server, sessionID, model) }.onFailure(::rethrowCancellation).isSuccess
                ) {
                    applied = model
                }
                log.info { "$sendID already on the server" }
                return admitted(sessionID, created, applied)
            }
            // The page is read now, once: never while browsing, never for a message that didn't come from it.
            val first = handle.current()
            val attachments: List<OpenCodeOutgoingAttachment> = first.attachments
            val request = first.page
            val page: OpenCodePageContext? = if (request != null) pageReader.read(request) else null
            var appliedModel = false
            while (true) {
                // Each attempt posts the send as it stands now (Move to Queue, Send Now), unless it was
                // deleted or stopped meanwhile.
                val pending = when (val attempt = handle.begin()) {
                    is PromptOutbox.Attempt.Post -> attempt.pending
                    PromptOutbox.Attempt.Cancelled, PromptOutbox.Attempt.Superseded -> return DeliveryOutcome.Withdrawn(sessionID, created)
                    PromptOutbox.Attempt.Stopped -> {
                        log.info { "$sendID stopped before it was sent" }
                        return failed(sessionID, created, OpenCodeClientError(SessionFeature.stoppedReason))
                    }
                }
                // Like the TUI, the composer's model is applied just before the prompt, never when it's
                // picked; the server ignores an unchanged one. A queued send waits behind the run, which
                // its model mustn't switch (`queuedPicks` applies it when the message starts). Agent
                // Selection: the chosen agent goes first, the same way. A refused switch fails the send
                // rather than posting it to the old agent.
                val agent = pending.agent
                if (agent != null && agentApplied != agent && !pending.waitsBehindRun) {
                    handle.networkOperation.started()
                    atAttempt = signals.counts
                    try {
                        openCodeClient.switchAgent(server, sessionID, agent)
                        agentApplied = agent
                        skippedLastWait = false
                    } catch (thrown: OpenCodeClientError) {
                        attempts += 1
                        log.notice { "$sendID couldn’t apply its agent to $sessionID: ${thrown.logSummary}" }
                        val error = if (thrown.isLocationMissing) thrown else thrown.copy(message = localized(R.string.session_switch_agent_failed, thrown.message))
                        if (!waitToRetry(error)) return failed(sessionID, created, error)
                        handle.networkOperation.retried()
                        continue
                    }
                }
                val model = pending.model
                if (model != null && !appliedModel && !pending.waitsBehindRun) {
                    handle.networkOperation.started()
                    atAttempt = signals.counts
                    try {
                        openCodeClient.switchModel(server, sessionID, model)
                        appliedModel = true
                        skippedLastWait = false
                    } catch (thrown: OpenCodeClientError) {
                        // A failed switch is a failed attempt of the send.
                        attempts += 1
                        log.notice { "$sendID couldn’t apply its model to $sessionID: ${thrown.logSummary}" }
                        val error = if (thrown.isLocationMissing) thrown else thrown.copy(message = localized(R.string.session_switch_model_failed, thrown.message))
                        if (!waitToRetry(error)) return failed(sessionID, created, error)
                        handle.networkOperation.retried()
                        continue
                    }
                }
                moveBackgroundWorkIfNeeded(sessionID)
                attemptStarting()
                log.info { "$sendID posting to $sessionID, attempt $attempts, ${pending.delivery.rawValue}" }
                val posted: OpenCodeClientError? = PromptContext.page.withValue(page) {
                    PromptContext.mentions.withValue(pending.mentions) {
                        PromptContext.quotes.withValue(pending.quotes.map { it.quote }) {
                            try {
                                openCodeClient.sendPrompt(
                                    server, sessionID, pending.text, attachments,
                                    pending.files.mapNotNull { it.reference }, pending.messageID, pending.delivery,
                                )
                                null
                            } catch (error: OpenCodeClientError) {
                                error
                            }
                        }
                    }
                }
                val applied = if (appliedModel) pending.model else null
                if (posted == null) {
                    log.info { "$sendID admitted on attempt $attempts in ${started.elapsedNow().logSummary}" }
                    return admitted(sessionID, created, applied)
                }
                // The POST may have been accepted even when its response was lost. Reconcile the same
                // client ID before trying again or offering a retry. When no connection was made, the
                // read can't get through either.
                if (posted.transport != TransportFailure.NotSent && serverHas(pending.messageID, sessionID, server, openCodeClient)) {
                    log.info { "$sendID found on the server after attempt $attempts failed (${posted.logSummary})" }
                    return admitted(sessionID, created, applied)
                }
                if (!waitToRetry(posted)) return failed(sessionID, created, posted)
                handle.networkOperation.retried()
            }
        } finally {
            signals.cancel()
        }
    }
    return PromptOutbox.Job { handle ->
        NetworkRetry.manualOperation.withValue(handle.networkOperation) {
            withRetryPolicy(RetryPolicy.once) { deliver(handle) }
        }
    }
}

/** Whether the session holds this message, delivered or still in its inbox. A failed read answers no: only a successful POST or a sighting settles it. */
private suspend fun serverHas(messageID: String, sessionID: String, server: OpenCodeServer, client: OpenCodeClient): Boolean {
    try {
        client.message(server, sessionID, messageID)
        return true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Not there, or the read failed: the inbox may have it.
    }
    val inbox = try {
        client.pendingInputs(server, sessionID)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
    return inbox?.any { it.id == messageID } == true
}

private fun rethrowCancellation(error: Throwable) {
    if (error is CancellationException) throw error
}
