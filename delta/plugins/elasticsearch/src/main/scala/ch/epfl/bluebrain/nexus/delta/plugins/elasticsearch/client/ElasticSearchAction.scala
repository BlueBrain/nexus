package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import io.circe.Json
import io.circe.syntax._

/**
  * Enumeration type for all possible bulk operations
  */
sealed trait ElasticSearchAction extends Product with Serializable {

  /**
    * @return
    *   the index to use for the current bulk operation
    */
  def index: IndexLabel

  /**
    * @return
    *   To route the document to a particular shard
    * @see
    *   https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping-routing-field.html
    */
  def routing: Option[String]

  /**
    * @return
    *   the id of the document for the current bulk operation
    */
  def id: String

  /**
    * @return
    *   the payload for the current bulk operation
    */
  def payload: String

  protected def json: Json =
    Json.fromFields(
      List("_index" := index.value, "_id" := id) ++ routing.map { r => "routing" := r }
    )
}

object ElasticSearchAction {

  private val newLine = System.lineSeparator()

  final case class Index(index: IndexLabel, id: String, routing: Option[String], content: Json)
      extends ElasticSearchAction {
    def payload: String = Json.obj("index" -> json).noSpaces + newLine + content.noSpaces
  }
  final case class Create(index: IndexLabel, id: String, routing: Option[String], content: Json)
      extends ElasticSearchAction {
    def payload: String = Json.obj("create" -> json).noSpaces + newLine + content.noSpaces
  }
  final case class Update(index: IndexLabel, id: String, routing: Option[String], content: Json, retry: Int = 0)
      extends ElasticSearchAction {
    val modified = if (retry > 0) json deepMerge Json.obj("retry_on_conflict" -> retry.asJson) else json

    def payload: String = Json.obj("update" -> modified).noSpaces + newLine + content.asJson.noSpaces
  }
  final case class Delete(index: IndexLabel, id: String, routing: Option[String]) extends ElasticSearchAction {
    def payload: String = Json.obj("delete" -> json).noSpaces + newLine
  }
}
