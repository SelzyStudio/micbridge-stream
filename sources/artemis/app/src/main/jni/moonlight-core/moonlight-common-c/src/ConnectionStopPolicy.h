#pragma once

#include <stdbool.h>
#include <stdatomic.h>

/** Only the serialized native lifetime owner may prepare/start a generation. */
typedef struct _LC_CONNECTION_STOP_POLICY {
    atomic_bool prepared;
    atomic_bool recoveryRequested;
} LC_CONNECTION_STOP_POLICY;

static inline void CspPrepareStart(LC_CONNECTION_STOP_POLICY* policy) {
    atomic_store_explicit(&policy->recoveryRequested, false, memory_order_release);
    atomic_store_explicit(&policy->prepared, true, memory_order_release);
}

/** A prepared generation preserves cancellation arriving before LiStartConnection enters. */
static inline bool CspBeginStart(LC_CONNECTION_STOP_POLICY* policy) {
    bool prepared = atomic_exchange_explicit(&policy->prepared, false, memory_order_acq_rel);
    if (!prepared) {
        // Standalone C clients that do not use prepare retain normal start semantics.
        atomic_store_explicit(&policy->recoveryRequested, false, memory_order_release);
    }
    return prepared;
}

static inline void CspRequestRecovery(LC_CONNECTION_STOP_POLICY* policy) {
    atomic_store_explicit(&policy->recoveryRequested, true, memory_order_release);
}

static inline bool CspIsRecoveryRequested(const LC_CONNECTION_STOP_POLICY* policy) {
    return atomic_load_explicit(&policy->recoveryRequested, memory_order_acquire);
}
