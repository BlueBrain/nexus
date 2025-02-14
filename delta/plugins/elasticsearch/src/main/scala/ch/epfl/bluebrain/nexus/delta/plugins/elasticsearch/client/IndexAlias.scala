package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import io.circe.JsonObject

/**
  * Allows to create an alias against the given index with the optional routing and filter
  * @param index
  *   the target index
  * @param alias
  *   the name of the alias to create
  * @param routing
  *   the optional routing to route requests for this alias to a specific shard
  * @param filter
  *   the optional filter to limit the documents an alias can access
  */
final case class IndexAlias(index: IndexLabel, alias: IndexLabel, routing: Option[String], filter: Option[JsonObject])
