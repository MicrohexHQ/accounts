package com.r3.corda.lib.accounts.workflows.services

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.*
import com.r3.corda.lib.accounts.workflows.flows.CreateAccount
import com.r3.corda.lib.accounts.workflows.flows.ShareAccountInfo
import com.r3.corda.lib.accounts.workflows.flows.ShareStateAndSyncAccounts
import com.r3.corda.lib.accounts.workflows.flows.ShareStateWithAccount
import com.r3.corda.lib.accounts.workflows.internal.persistentKey
import com.r3.corda.lib.accounts.workflows.internal.publicKeyHashToAccountId
import com.r3.corda.lib.accounts.workflows.internal.publicKeyHashToExternalId
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.toStringShort
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.asCordaFuture
import net.corda.core.internal.concurrent.doneFuture
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.queryBy
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.contextLogger
import java.security.PublicKey
import java.util.*
import java.util.concurrent.CompletableFuture

@CordaService
class KeyManagementBackedAccountService(val services: AppServiceHub) : AccountService, SingletonSerializeAsToken() {

    companion object {
        val logger = contextLogger()
    }

    @Suspendable
    override fun accountsForHost(host: Party): List<StateAndRef<AccountInfo>> {
        return services.vaultService.queryBy<AccountInfo>(accountBaseCriteria.and(accountHostCriteria(host))).states
    }

    @Suspendable
    override fun ourAccounts(): List<StateAndRef<AccountInfo>> {
        return accountsForHost(services.ourIdentity)
    }

    @Suspendable
    override fun allAccounts(): List<StateAndRef<AccountInfo>> {
        return services.vaultService.queryBy<AccountInfo>(accountBaseCriteria).states
    }

    @Suspendable
    override fun accountInfo(id: UUID): StateAndRef<AccountInfo>? {
        val uuidCriteria = accountUUIDCriteria(id)
        return services.vaultService.queryBy<AccountInfo>(accountBaseCriteria.and(uuidCriteria)).states.singleOrNull()
    }

    @Suspendable
    override fun accountInfo(name: String): List<StateAndRef<AccountInfo>> {
        val nameCriteria = accountNameCriteria(name)
        val results = services.vaultService.queryBy<AccountInfo>(accountBaseCriteria.and(nameCriteria)).states
        return when (results.size) {
            0 -> emptyList()
            1 -> listOf(results.single())
            else -> {
                logger.warn("WARNING: Querying for account by name returned more than one account, this is likely " +
                        "because another node shared an account with this node that has the same name as an " +
                        "account already created on this node. Filtering the results by host will allow you to access" +
                        "the AccountInfo you need.")
                results
            }
        }
    }

    @Suspendable
    override fun createAccount(name: String): CordaFuture<StateAndRef<AccountInfo>> {
        return flowAwareStartFlow(CreateAccount(name))
    }

    override fun <T : StateAndRef<*>> shareStateAndSyncAccounts(state: T, party: Party): CordaFuture<Unit> {
        return flowAwareStartFlow(ShareStateAndSyncAccounts(state, party))
    }

    @Suspendable
    override fun accountKeys(id: UUID): List<PublicKey> {
        // TODO: Temporary solution for now. See if we can use the identity service to store PUB KEY -> EXT ID mapping.
        return services.withEntityManager {
            val query = createQuery(
                    """
                        select a.publicKey
                        from $persistentKey a, $publicKeyHashToExternalId b
                        where b.externalId = :uuid
                        and b.publicKeyHash = a.publicKeyHash
                    """,
                    ByteArray::class.java
            )
            query.setParameter("uuid", id)
            query.resultList.map { Crypto.decodePublicKey(it) }
        } + services.withEntityManager {
            val query = createQuery(
                    """
                        select c.publicKey
                        from $publicKeyHashToAccountId c
                        where c.externalId = :uuid
                    """,
                    ByteArray::class.java
            )
            query.setParameter("uuid", id)
            query.resultList.map { Crypto.decodePublicKey(it) }
        }
    }

    @Suspendable
    override fun accountIdForKey(owningKey: PublicKey): UUID? {
        // 1. Check the KMS for our keys.
        // 2. Check the accounts service for other node's keys.
        // 3. Return null if no results from 1 and 2.
        return services.keyManagementService.externalIdForPublicKey(owningKey)?.let {
            it
        } ?: services.withEntityManager {
            val query = createQuery(
                    """
                        select a.externalId
                        from $publicKeyHashToAccountId a
                        where a.publicKeyHash = :publicKeyHash
                    """,
                    UUID::class.java
            )
            query.setParameter("publicKeyHash", owningKey.toStringShort())
            query.resultList.firstOrNull()
        }
    }

    @Suspendable
    override fun accountInfo(owningKey: PublicKey): StateAndRef<AccountInfo>? {
        return accountIdForKey(owningKey)?.let { accountInfo(it) }
    }

    @Suspendable
    override fun shareAccountInfoWithParty(accountId: UUID, party: Party): CordaFuture<Unit> {
        val foundAccount = accountInfo(accountId)
        return if (foundAccount != null) {
            flowAwareStartFlow(ShareAccountInfo(foundAccount, listOf(party)))
        } else {
            CompletableFuture<Unit>().also {
                it.completeExceptionally(IllegalStateException("Account: $accountId was not found on this node"))
            }.asCordaFuture()
        }
    }

    @Suspendable
    override fun <T : ContractState> shareStateWithAccount(accountId: UUID, state: StateAndRef<T>): CordaFuture<Unit> {
        val foundAccount = accountInfo(accountId)
        return if (foundAccount != null) {
            flowAwareStartFlow(ShareStateWithAccount(accountInfo = foundAccount.state.data, state = state))
        } else {
            CompletableFuture<Unit>().also {
                it.completeExceptionally(IllegalStateException("Account: $accountId was not found on this node"))
            }.asCordaFuture()
        }
    }

    @Suspendable
    private inline fun <reified T : Any> flowAwareStartFlow(flowLogic: FlowLogic<T>): CordaFuture<T> {
        val currentFlow = FlowLogic.currentTopLevel
        return if (currentFlow != null) {
            val result = currentFlow.subFlow(flowLogic)
            doneFuture(result)
        } else {
            this.services.startFlow(flowLogic).returnValue
        }
    }
}