package ch.epfl.bluebrain.nexus.tests

import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import io.circe.{Json, JsonObject}
import io.circe.optics.JsonPath.root
import org.scalatest.{Assertion, OptionValues}
import org.scalatest.matchers.should.Matchers

trait Optics extends Matchers with OptionValues

object Optics extends Optics {

  def filterKey(key: String): Json => Json = filterKeys(Set(key))

  def filterKeys(keys: Set[String]): Json => Json =
    keys.map { root.at(_).set(None) }.reduce(_ andThen _)

  def filterNestedKeys(keys: String*): Json => Json = {
    def inner(jsonObject: JsonObject): JsonObject = {
      val filtered = keys.foldLeft(jsonObject) { (o, k) => o.remove(k) }
      JsonObject.fromIterable(
        filtered.toList.map { case (k, v) =>
          v.asObject.fold(k -> v) { o =>
            k -> Json.fromJsonObject(
              inner(o)
            )
          }
        }
      )
    }
    root.obj.modify(inner)
  }

  private val realmKeysToIgnore     = Set("_createdAt", "_createdBy", "_updatedAt", "_updatedBy")
  val filterRealmKeys: Json => Json = filterKeys(realmKeysToIgnore)

  private val metadataKeys             = Set("_uuid", "_createdAt", "_updatedAt", "_organizationUuid")
  val filterMetadataKeys: Json => Json = filterKeys(metadataKeys)

  val filterResultMetadata: Json => Json = root._results.arr.modify(_.map(filterMetadataKeys))

  val filterSearchMetadata: Json => Json = filterKey("_next") andThen filterResultMetadata

  val `@id` = root.`@id`.string
  val _uuid = root._uuid.string

  val _total = root._total.long

  val hits       = root.hits.hits
  val hitsSource = hits.each._source.json

  val location = root._location.string

  object admin {
    val `@type` = root.`@type`.string

    val _label      = root._label.string
    val description = root.description.string
    val _rev        = root._rev.long
    val _deprecated = root._deprecated.boolean

    val base        = root.base.string
    val vocab       = root.vocab.string
    val apiMappings = root.apiMappings.json

    def validate(
        json: Json,
        tpe: String,
        idPrefix: String,
        id: String,
        desc: String,
        rev: Long,
        label: String,
        deprecated: Boolean = false
    )(implicit config: TestsConfig): Assertion = {
      `@id`.getOption(json).value shouldEqual s"${config.deltaUri.toString()}/$idPrefix/$id"
      `@type`.getOption(json).value shouldEqual tpe
      _uuid
        .getOption(json)
        .value should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
      _label.getOption(json).value shouldEqual label
      description.getOption(json).value shouldEqual desc
      _rev.getOption(json).value shouldEqual rev
      _deprecated.getOption(json).value shouldEqual deprecated
    }

    def validateProject(response: Json, payload: Json): Assertion = {
      base.getOption(response) shouldEqual base.getOption(payload)
      vocab.getOption(response) shouldEqual vocab.getOption(payload)
      apiMappings.getOption(response) shouldEqual apiMappings.getOption(payload)
    }

  }

  object keycloak {
    val access_token = root.access_token.string
  }

  object error {
    val `@type` = root.`@type`.string
  }

  object resources {
    val _next    = root._next.string
    val _results = root._results.arr
  }

  object events {
    val filterFields = filterKeys(Set("_instant", "_updatedAt"))
      .andThen(
        List("_location", "_uuid", "_path")
          .map { root._attributes.at(_).set(None) }
          .reduce(_ andThen _)
      )
  }

}
