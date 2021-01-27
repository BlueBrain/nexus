package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import io.circe.syntax._
import io.circe.{Json, JsonObject}

/**
  * Enumeration type for all possible bulk operations
  */
sealed trait ElasticSearchBulk extends Product with Serializable {

  /**
    * @return the index to use for the current bulk operation
    */
  def index: String

  /**
    * @return the id of the document for the current bulk operation
    */
  def id: String

  /**
    * @return the payload for the current bulk operation
    */
  def payload: String

  protected def json: Json =
    Json.obj("_index" -> index.asJson, "_id" -> id.asJson)
}

object ElasticSearchBulk {

  private val newLine = System.lineSeparator()

  final case class Index(index: String, id: String, content: JsonObject)                  extends ElasticSearchBulk {
    def payload: String = Json.obj("index" -> json).noSpaces + newLine + content.asJson.noSpaces
  }
  final case class Create(index: String, id: String, content: JsonObject)                 extends ElasticSearchBulk {
    def payload: String = Json.obj("create" -> json).noSpaces + newLine + content.asJson.noSpaces
  }
  final case class Update(index: String, id: String, content: JsonObject, retry: Int = 0) extends ElasticSearchBulk {
    val modified = if (retry > 0) json deepMerge Json.obj("retry_on_conflict" -> retry.asJson) else json

    def payload: String = Json.obj("update" -> modified).noSpaces + newLine + content.asJson.noSpaces
  }
  final case class Delete(index: String, id: String)                                      extends ElasticSearchBulk {
    def payload: String = Json.obj("delete" -> json).noSpaces + newLine
  }
}
