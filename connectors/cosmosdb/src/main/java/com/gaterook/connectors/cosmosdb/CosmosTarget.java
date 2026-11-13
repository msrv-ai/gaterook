package com.gaterook.connectors.cosmosdb;

import tools.jackson.databind.JsonNode;

/**
 * The container a call names, checked: both IDs are names Cosmos DB takes ({@link CosmosIds}) and the connection's
 * lists allow the database and the container. The same checks run in {@code classify} and again in {@code execute}.
 */
record CosmosTarget(String database, String container) {

  /** The database of {@code args}, on the connection's list. */
  static String database(JsonNode args, CosmosSettings s) throws Refusal {
    String db = name(args, "database");
    if (!s.allowsDatabase(db)) {
      throw new Refusal(Refusal.DATABASE_NOT_ALLOWED, CosmosIds.shown(db) + " isn't one of the databases agents may "
          + "use on this connection.", "Call cosmosdb_list_databases to see the databases you can use.");
    }
    return db;
  }

  /** The container of {@code args}, both lists allowing it. */
  static CosmosTarget of(JsonNode args, CosmosSettings s) throws Refusal {
    String db = database(args, s);
    String container = name(args, "container");
    if (!s.allowsContainer(db, container)) {
      throw new Refusal(Refusal.CONTAINER_NOT_ALLOWED, CosmosIds.shown(db) + "/" + CosmosIds.shown(container)
          + " isn't one of the containers agents may use on this connection.", "Call cosmosdb_list_containers to see "
          + "the containers you can use.");
    }
    return new CosmosTarget(db, container);
  }

  private static String name(JsonNode args, String field) throws Refusal {
    JsonNode v = args.path(field);
    if (!v.isString()) {
      throw Refusal.bad(field + " is the " + field + "'s ID, a string.", "Call cosmosdb_list_"
          + (field.equals("database") ? "databases" : "containers") + " to see them.");
    }
    String problem = CosmosIds.nameProblem(v.asString());
    if (problem != null) {
      throw Refusal.bad("The " + field + " " + CosmosIds.shown(v.asString()) + " " + problem + ".", null);
    }
    return v.asString();
  }

  /** {@code dbs/app/colls/orders}, as signed. */
  String link() {
    return "dbs/" + database + "/colls/" + container;
  }

  /** {@code /dbs/app/colls/orders}, encoded. */
  String path() {
    return "/dbs/" + CosmosIds.encode(database) + "/colls/" + CosmosIds.encode(container);
  }

  /** {@code app/orders}, for messages. */
  String shown() {
    return CosmosIds.shown(database) + "/" + CosmosIds.shown(container);
  }
}
