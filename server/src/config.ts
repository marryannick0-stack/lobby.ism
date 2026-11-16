/**
 * Every tunable server value lives here (project rule: never hardcode tunables).
 * The server is the authority (R4): the client only sends raw data, the server
 * decides what is accepted.
 */
var GameConfig = {
    node: {
        /** Maximum length of a TEXT node, in characters. Must match the client. */
        textMaxChars: 500,
        /** Largest search radius a client may ask for, in metres. */
        nearbyMaxRadiusM: 500,
        /** Most nodes returned by one nearby query. */
        nearbyLimit: 200,
        /** How many nodes one account may place per hour. */
        createPerHour: 30,
        /**
         * Phase 0a: only TEXT. Further types are switched on here once the
         * client can render them.
         */
        allowedContentTypes: ["TEXT"],
        /**
         * Status given to a newly placed node.
         * Phase 0a/0b: founder and trusted testers place directly -> LIVE.
         * From Phase 1 on: set to PENDING_REVIEW to route through moderation.
         */
        initialStatus: "LIVE",
    },
};
