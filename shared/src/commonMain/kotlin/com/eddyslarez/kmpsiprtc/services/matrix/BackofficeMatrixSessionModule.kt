package com.eddyslarez.kmpsiprtc.services.matrix

import net.folivo.trixnity.client.store.repository.AccountRepository
import net.folivo.trixnity.client.store.repository.InMemoryAccountRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/** Stores Matrix credentials only for the lifetime of this MatrixClient. */
internal fun createBackofficeInMemoryAccountRepositoryModule(): Module = module {
    single<AccountRepository> { InMemoryAccountRepository() }
}
