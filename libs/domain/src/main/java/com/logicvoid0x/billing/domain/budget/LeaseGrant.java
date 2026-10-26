package com.logicvoid0x.billing.domain.budget;

/**
 * A lease granted to a gateway host.
 *
 * @param granted amount granted, in the budget's unit (0 when refused)
 * @param epoch the shard's current epoch (fencing token)
 * @param staleEpoch whether the request carried an older epoch and was refused
 * @param leaseId the lease's id on this host and budget, used to report it; 0 when nothing was
 *     granted
 */
public record LeaseGrant(long granted, long epoch, boolean staleEpoch, long leaseId) {}
