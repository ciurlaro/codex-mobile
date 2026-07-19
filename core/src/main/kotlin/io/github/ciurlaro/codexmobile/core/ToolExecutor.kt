package io.github.ciurlaro.codexmobile.core

import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ToolExecutor(
    tools: Collection<DeviceTool>,
    private val mutationJournal: MutationJournal? = null,
    private val approvalPolicy: ApprovalPolicy,
) {
    private val toolsByName = tools.associateBy(DeviceTool::name)
    private val preparedPlans = Collections.synchronizedMap(IdentityHashMap<ToolPlan, Boolean>())

    init {
        require(toolsByName.size == tools.size) { "Device tool names must be unique" }
    }

    suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
        if (call.id.value.isBlank()) throw ToolRejectedException("Tool call ID is missing")
        val tool = toolsByName[call.name]
            ?: throw ToolRejectedException("Unknown device tool")
        val plan = tool.prepare(call, scopeId)
        try {
            check(plan.call == call && plan.scopeId == scopeId && plan.effect == tool.effect) {
                "Device tool returned an inconsistent plan"
            }
            check(plan.effect != ToolEffect.MUTATION || plan.approvalPreview != null) {
                "Mutation plan is missing its resolved approval preview"
            }
            synchronized(preparedPlans) {
                check(preparedPlans.size < MAX_PREPARED_PLANS) { "Too many device tool plans are pending" }
                preparedPlans[plan] = true
            }
            return plan
        } catch (error: Throwable) {
            tool.abandon(plan)
            throw error
        }
    }

    suspend fun execute(plan: ToolPlan, approval: UserApproval? = null): ToolResult {
        if (preparedPlans.remove(plan) != true) {
            return ToolResult.Rejected(plan.call.id, "Device tool plan was not prepared or was already used")
        }
        val tool = toolsByName[plan.call.name]
            ?: return ToolResult.Rejected(plan.call.id, "Device tool is not registered")
        if (plan.effect != tool.effect) {
            tool.abandon(plan)
            return ToolResult.Rejected(plan.call.id, "Device tool plan is invalid")
        }
        val requirement = approvalPolicy.requirement(plan).let {
            if (plan.effect == ToolEffect.MUTATION && it == ApprovalRequirement.ALLOW) {
                ApprovalRequirement.USER
            } else {
                it
            }
        }
        when (requirement) {
            ApprovalRequirement.DENY -> {
                tool.abandon(plan)
                return ToolResult.Rejected(plan.call.id, "Device policy denied this operation")
            }

            ApprovalRequirement.USER -> if (approval?.consume(plan) != true) {
                tool.abandon(plan)
                return ToolResult.Rejected(plan.call.id, "Matching user approval is required")
            }

            ApprovalRequirement.ALLOW -> Unit
        }
        return if (plan.effect == ToolEffect.MUTATION) {
            executeMutation(tool, plan)
        } else {
            executeTool(tool, plan)
        }
    }

    fun abandon(plan: ToolPlan): Boolean {
        if (preparedPlans.remove(plan) != true) return false
        toolsByName[plan.call.name]?.abandon(plan)
        return true
    }

    suspend fun reconcileUnresolved(): List<MutationRecord> {
        val journal = mutationJournal ?: return emptyList()
        journal.unresolved().forEach { original ->
            var record = original
            if (record.state == MutationState.PREPARED) {
                journal.transition(
                    record.id,
                    MutationState.PREPARED,
                    MutationState.EXECUTING,
                    "Recovery proved provider dispatch never began",
                )
                journal.transition(
                    record.id,
                    MutationState.EXECUTING,
                    MutationState.FAILED,
                    "Mutation was not dispatched",
                )
                return@forEach
            }
            if (record.state == MutationState.EXECUTING) {
                journal.transition(
                    record.id,
                    MutationState.EXECUTING,
                    MutationState.UNKNOWN,
                    "Process ended after dispatch became possible",
                )
                record = checkNotNull(journal.find(record.id))
            }
            if (record.state != MutationState.UNKNOWN) return@forEach

            val result = try {
                toolsByName[record.toolName]?.reconcile(record)
                    ?: ToolResult.Unknown(record.callId, "Mutation tool is no longer registered")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ToolResult.Unknown(record.callId, "Android mutation state could not be re-observed")
            }
            when (result) {
                is ToolResult.Success -> journal.transition(
                    record.id,
                    MutationState.UNKNOWN,
                    MutationState.SUCCEEDED,
                    "Android re-observed the expected state",
                )

                is ToolResult.Failed -> journal.transition(
                    record.id,
                    MutationState.UNKNOWN,
                    MutationState.FAILED,
                    result.message,
                )

                is ToolResult.Rejected, is ToolResult.Unknown -> Unit
            }
        }
        return journal.visible()
    }

    suspend fun visibleMutationRecords(): List<MutationRecord> =
        mutationJournal?.visible().orEmpty()

    suspend fun acknowledgeMutation(recordId: MutationRecordId) {
        mutationJournal?.acknowledge(recordId)
    }

    suspend fun pruneResolvedMutations(updatedBeforeMillis: Long): Int =
        mutationJournal?.pruneResolved(updatedBeforeMillis) ?: 0

    fun retrySafety(record: MutationRecord, candidate: ToolPlan): MutationRetrySafety {
        if (
            record.state != MutationState.FAILED || candidate.effect != ToolEffect.MUTATION ||
            record.toolName != candidate.call.name || record.scopeId != candidate.scopeId ||
            !preparedPlans.containsKey(candidate)
        ) {
            return MutationRetrySafety.NEVER
        }
        return toolsByName[record.toolName]?.retrySafety(record, candidate)
            ?: MutationRetrySafety.NEVER
    }

    private suspend fun executeTool(tool: DeviceTool, plan: ToolPlan): ToolResult = try {
        tool.execute(plan)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ToolResult.Failed(plan.call.id, "tool_failure", "Android tool execution failed")
    }

    private suspend fun executeMutation(tool: DeviceTool, plan: ToolPlan): ToolResult {
        val journal = mutationJournal ?: run {
            tool.abandon(plan)
            return ToolResult.Failed(
                plan.call.id,
                "journal_unavailable",
                "Durable mutation recovery is unavailable",
            )
        }
        val record = try {
            MutationRecord(
                id = MutationRecordId(UUID.randomUUID().toString()),
                callId = plan.call.id,
                toolName = plan.call.name,
                scopeId = plan.scopeId,
                planFingerprint = plan.fingerprint,
                recoveryPayload = checkNotNull(tool.recoveryPayload(plan)) {
                    "Mutation tool did not provide recovery data"
                },
                state = MutationState.PREPARED,
            ).also { journal.create(it) }
        } catch (error: CancellationException) {
            tool.abandon(plan)
            throw error
        } catch (_: Exception) {
            tool.abandon(plan)
            return ToolResult.Failed(
                plan.call.id,
                "journal_unavailable",
                "Mutation was not dispatched because recovery state could not be saved",
            )
        }

        try {
            journal.transition(record.id, MutationState.PREPARED, MutationState.EXECUTING)
        } catch (error: CancellationException) {
            tool.abandon(plan)
            throw error
        } catch (_: Exception) {
            tool.abandon(plan)
            return ToolResult.Failed(
                plan.call.id,
                "journal_unavailable",
                "Mutation was not dispatched because execution state could not be saved",
            )
        }

        val result = try {
            executeTool(tool, plan)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    journal.transition(
                        record.id,
                        MutationState.EXECUTING,
                        MutationState.UNKNOWN,
                        "Execution was cancelled after dispatch became possible",
                    )
                }
            }
            throw error
        }
        val (state, outcome, acknowledged) = when (result) {
            is ToolResult.Success -> Triple(
                MutationState.SUCCEEDED,
                "Android observed the expected state",
                true,
            )

            is ToolResult.Rejected -> Triple(MutationState.FAILED, result.reason, true)
            is ToolResult.Failed -> Triple(MutationState.FAILED, result.message, true)
            is ToolResult.Unknown -> Triple(MutationState.UNKNOWN, result.reason, false)
        }
        return try {
            withContext(NonCancellable) {
                journal.transition(record.id, MutationState.EXECUTING, state, outcome, acknowledged)
            }
            result
        } catch (_: Exception) {
            ToolResult.Unknown(
                plan.call.id,
                "Android observed an outcome but durable recovery state could not be updated",
            )
        }
    }

    private companion object {
        const val MAX_PREPARED_PLANS = 64
    }
}
