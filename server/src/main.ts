/**
 * Nakama entry point. Nakama calls InitModule once at start-up.
 *
 * Nakama reads this function to find out which server functions exist, so
 * InitModule and every registered function must be plain, named, top-level
 * functions (no arrow functions, no variables holding functions).
 */
function InitModule(
    _ctx: nkruntime.Context,
    logger: nkruntime.Logger,
    nk: nkruntime.Nakama,
    initializer: nkruntime.Initializer
): void {
    ensureNodeSchema(nk, logger);
    initializer.registerRpc("node_create", rpcNodeCreate);
    initializer.registerRpc("nodes_nearby", rpcNodesNearby);
    logger.info("lobbyism: module loaded");
}
