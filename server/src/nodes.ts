/**
 * Node system (GDD v4, chapter 04): content anchored to a GPS position.
 *
 * Table design notes:
 *  - location is PostGIS GEOGRAPHY, so distances are real metres on the globe.
 *  - content_type / content_status / spawn_mode are TEXT with CHECK constraints
 *    instead of Postgres ENUMs: adding a value is a one-line change, an ENUM
 *    would need a migration.
 *  - content_status and creator_revenue exist from day one although Phase 0a
 *    does not use them yet. Reserving them now avoids a schema migration later.
 */
var NODE_SCHEMA: string[] = [
    "CREATE EXTENSION IF NOT EXISTS postgis",
    "CREATE TABLE IF NOT EXISTS lobbyism_node (" +
    "  id UUID PRIMARY KEY," +
    "  location GEOGRAPHY(POINT, 4326) NOT NULL," +
    "  content_type TEXT NOT NULL CHECK (content_type IN ('TEXT','IMAGE','AUDIO','VIDEO','LORE','LOOT','ART_INSTALLATION','COMMUNITY_EVENT','COMBAT_SPAWN'))," +
    "  content_payload JSONB NOT NULL," +
    "  spawn_mode TEXT NOT NULL DEFAULT 'MANUAL' CHECK (spawn_mode IN ('MANUAL','AUTO'))," +
    "  owner_id UUID NULL," +
    "  content_status TEXT NOT NULL DEFAULT 'LIVE' CHECK (content_status IN ('DRAFT','PENDING_REVIEW','LIVE','FLAGGED','REMOVED'))," +
    "  created_at TIMESTAMPTZ NOT NULL DEFAULT now()," +
    "  creator_revenue JSONB NULL" +
    ")",
    "CREATE INDEX IF NOT EXISTS lobbyism_node_location_idx ON lobbyism_node USING GIST (location)",
    "CREATE INDEX IF NOT EXISTS lobbyism_node_owner_created_idx ON lobbyism_node (owner_id, created_at)",
];

var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function ensureNodeSchema(nk: nkruntime.Nakama, logger: nkruntime.Logger): void {
    for (var i = 0; i < NODE_SCHEMA.length; i++) {
        nk.sqlExec(NODE_SCHEMA[i], []);
    }
    logger.info("lobbyism: node schema ready");
}

/** Parses the request body. A client error becomes a clean 400, never a stack trace. */
function parseJson(payload: string): any {
    try {
        var parsed = JSON.parse(payload || "{}");
        if (parsed === null || typeof parsed !== "object") throw new Error("not an object");
        return parsed;
    } catch (e) {
        throw invalid("body must be a JSON object");
    }
}

function invalid(message: string): nkruntime.Error {
    return { message: message, code: nkruntime.Codes.INVALID_ARGUMENT };
}

function requireUser(ctx: nkruntime.Context): string {
    if (!ctx.userId) {
        throw { message: "login required", code: nkruntime.Codes.UNAUTHENTICATED } as nkruntime.Error;
    }
    return ctx.userId;
}

function isFiniteNumber(v: any): boolean {
    return typeof v === "number" && isFinite(v);
}

function validCoordinates(lat: any, lon: any): boolean {
    return isFiniteNumber(lat) && isFiniteNumber(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

/**
 * RPC node_create: the player leaves a node at a position.
 *
 * Request:  { id: uuid, lat: number, lon: number, content_type: "TEXT", payload: string }
 * Response: { id, owner_id, content_status }
 *
 * The id is created by the client. Sending the same node twice (e.g. after a
 * lost connection) is harmless: the second insert is ignored.
 */
function rpcNodeCreate(ctx: nkruntime.Context, logger: nkruntime.Logger, nk: nkruntime.Nakama, payload: string): string {
    var userId = requireUser(ctx);
    var req = parseJson(payload);
    var cfg = GameConfig.node;

    if (typeof req.id !== "string" || !UUID_PATTERN.test(req.id)) throw invalid("id must be a UUID");
    if (!validCoordinates(req.lat, req.lon)) throw invalid("lat/lon out of range");
    if (cfg.allowedContentTypes.indexOf(req.content_type) < 0) throw invalid("content_type not enabled");
    if (typeof req.payload !== "string") throw invalid("payload must be a string");

    var text = req.payload.replace(/^\s+|\s+$/g, "");
    if (text.length === 0) throw invalid("payload is empty");
    if (text.length > cfg.textMaxChars) throw invalid("payload too long");

    // Rate limit per account, checked against the database so a restart cannot reset it.
    var recent = nk.sqlQuery(
        "SELECT count(*)::int AS n FROM lobbyism_node WHERE owner_id = $1::uuid AND created_at > now() - interval '1 hour'",
        [userId]
    );
    if (recent.length > 0 && Number(recent[0].n) >= cfg.createPerHour) {
        throw { message: "rate limit reached", code: nkruntime.Codes.RESOURCE_EXHAUSTED } as nkruntime.Error;
    }

    // PostGIS point order is (longitude, latitude).
    nk.sqlExec(
        "INSERT INTO lobbyism_node (id, location, content_type, content_payload, owner_id, content_status) " +
        "VALUES ($1::uuid, ST_SetSRID(ST_MakePoint($2::float8, $3::float8), 4326)::geography, $4, $5::jsonb, $6::uuid, $7) " +
        "ON CONFLICT (id) DO NOTHING",
        [req.id, req.lon, req.lat, req.content_type, JSON.stringify({ text: text }), userId, cfg.initialStatus]
    );

    // Deliberately no position in the log (data minimisation).
    logger.info("lobbyism: node %s created by %s", req.id, userId);
    return JSON.stringify({ id: req.id, owner_id: userId, content_status: cfg.initialStatus });
}

/**
 * RPC nodes_nearby: all LIVE nodes around a position.
 *
 * Request:  { lat: number, lon: number, radius_m: number }
 * Response: { nodes: [{ id, lat, lon, content_type, payload, owner_id, content_status, created_at_ms }] }
 *
 * The player's position is used for this one query and is neither stored nor logged.
 */
function rpcNodesNearby(ctx: nkruntime.Context, _logger: nkruntime.Logger, nk: nkruntime.Nakama, payload: string): string {
    requireUser(ctx);
    var req = parseJson(payload);
    var cfg = GameConfig.node;

    if (!validCoordinates(req.lat, req.lon)) throw invalid("lat/lon out of range");
    var radius = isFiniteNumber(req.radius_m) ? req.radius_m : cfg.nearbyMaxRadiusM;
    radius = Math.max(1, Math.min(radius, cfg.nearbyMaxRadiusM));

    // id and owner_id are cast to text so they arrive as plain strings.
    var rows = nk.sqlQuery(
        "SELECT id::text AS id, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon, " +
        "content_type, content_payload->>'text' AS payload, owner_id::text AS owner_id, content_status, " +
        "(extract(epoch FROM created_at) * 1000)::float8 AS created_at_ms " +
        "FROM lobbyism_node " +
        "WHERE content_status = 'LIVE' " +
        "AND ST_DWithin(location, ST_SetSRID(ST_MakePoint($1::float8, $2::float8), 4326)::geography, $3::float8) " +
        "ORDER BY created_at DESC LIMIT $4::int",
        [req.lon, req.lat, radius, cfg.nearbyLimit]
    );

    var nodes: any[] = [];
    for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        nodes.push({
            id: r.id,
            lat: Number(r.lat),
            lon: Number(r.lon),
            content_type: r.content_type,
            payload: r.payload || "",
            owner_id: r.owner_id || null,
            content_status: r.content_status,
            created_at_ms: Math.round(Number(r.created_at_ms)),
        });
    }
    return JSON.stringify({ nodes: nodes });
}
