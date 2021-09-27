package ch.epfl.bluebrain.nexus.tests

import ch.epfl.bluebrain.nexus.testkit.{CirceEq, TestHelpers}
import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import io.circe.optics.JsonPath.root
import io.circe.{Json, JsonObject}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, OptionValues}

trait Optics extends Matchers with OptionValues with CirceEq with TestHelpers

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

  private val projectKeysToIgnore             = metadataKeys + "_effectiveApiMappings"
  val filterProjectMetadataKeys: Json => Json = filterKeys(projectKeysToIgnore)

  val filterResultMetadata: Json => Json = root._results.arr.modify(_.map(filterMetadataKeys))

  val filterSearchMetadata: Json => Json = filterKey("_next") andThen filterResultMetadata

  val `@id` = root.`@id`.string
  val _uuid = root._uuid.string

  val _total = root._total.long

  val hits       = root.hits.hits
  val hitsSource = hits.each._source.json

  val location = root._location.string

  val defaultMappings = jsonContentOf("admin/projects/default-mappings.json").asArray.get

  object admin {
    val `@type` = root.`@type`.string

    val _label             = root._label.string
    val description        = root.description.string
    val _rev               = root._rev.long
    val _deprecated        = root._deprecated.boolean
    val _markedForDeletion = root._markedForDeletion.boolean

    val progress  = root.progress.string
    val _finished = root._finished.boolean

    val base                 = root.base.string
    val vocab                = root.vocab.string
    val apiMappings          = root.apiMappings.json
    val effectiveApiMappings = root._effectiveApiMappings.json

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
      val expectedMappings          = apiMappings
        .getOption(payload)
        .flatMap(_.asArray)
        .map(_.toSet)
        .map(Json.fromValues)
      apiMappings.getOption(response).value should equalIgnoreArrayOrder(expectedMappings.value)
      val expectedEffectiveMappings = apiMappings
        .getOption(payload)
        .flatMap(_.asArray)
        .map(_.map { entry =>
          val ns     = entry.hcursor.get[String]("namespace").toOption.value
          val prefix = entry.hcursor.get[String]("prefix").toOption.value
          Json.obj(
            "_namespace" -> Json.fromString(ns),
            "_prefix"    -> Json.fromString(prefix)
          )
        })
        .map(_.toSet ++ defaultMappings)
        .map(Json.fromValues)
      effectiveApiMappings.getOption(response).value should equalIgnoreArrayOrder(expectedEffectiveMappings.value)
    }

  }

  object keycloak {
    val access_token = root.access_token.string
  }

  object error {
    val `@type` = root.`@type`.string
  }

  object listing {
    val _next      = root._next.string
    val eachResult = root._results.each
    val _results   = root._results.arr
    val _total     = root._total.long
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
