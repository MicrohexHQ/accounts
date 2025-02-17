package com.r3.corda.lib.accounts.workflows.internal.schemas

import net.corda.core.schemas.DirectStatePersistable
import net.corda.core.schemas.PersistentStateRef
import org.hibernate.annotations.Type
import java.util.*
import javax.persistence.*

@Entity
@Table(name = "account_to_state_refs", indexes = [Index(name = "external_id_idx", columnList = "external_id")])
data class AllowedToSeeStateMapping(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        var id: Long?,

        @Column(name = "external_id", unique = false, nullable = false)
        @Type(type = "uuid-char")
        var externalId: UUID?,

        override var stateRef: PersistentStateRef?
) : DirectStatePersistable {
    constructor() : this(null, null, null)
}