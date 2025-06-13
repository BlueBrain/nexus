package ai.senscience.nexus.tests

import ai.senscience.nexus.tests.Optics.*
import ai.senscience.nexus.tests.Optics.admin.{apiMappings, effectiveApiMappings, enforceSchema}
import ai.senscience.nexus.tests.admin.ProjectPayload
import ai.senscience.nexus.tests.config.TestsConfig
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.testkit.scalatest.{ClasspathResources, ScalaTestExtractValue}
import io.circe.optics.JsonPath.root
import io.circe.{Json, JsonObject}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, Assertions, OptionValues}

trait OpticsValidators
    extends Matchers
    with OptionValues
    with CirceEq
    with ClasspathResources
    with ScalaTestExtractValue {
  self: Assertions =>

  def validate(
      json: Json,
      tpe: String,
      idPrefix: String,
      id: String,
      desc: String,
      rev: Int,
      label: String,
      deprecated: Boolean = false
  )(implicit config: TestsConfig): Assertion = {
    `@id`.getOption(json).value shouldEqual s"${config.deltaUri.toString()}/$idPrefix/$id"
    `@type`.getOption(json).value shouldEqual tpe
    _uuid
      .getOption(json)
      .value should fullyMatch regex """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
    admin._label.getOption(json).value shouldEqual label
    admin.description.getOption(json).value shouldEqual desc
    admin._rev.getOption(json).value shouldEqual rev
    admin._deprecated.getOption(json).value shouldEqual deprecated
  }

  def validateProject(response: Json, payload: ProjectPayload): Assertion = {
    admin.base.getOption(response).value shouldEqual payload.base
    admin.vocab.getOption(response) shouldEqual payload.vocab

    apiMappings.fold(response) shouldEqual payload.apiMappings

    val expectedEffectiveMappings = payload.apiMappings ++ DefaultApiMappings.value
    effectiveApiMappings.fold(response) shouldEqual expectedEffectiveMappings

    enforceSchema.getOption(response).value shouldEqual payload.enforceSchema
  }
}

object Optics {

  def filterKey(key: String): Json => Json = filterKeys(Set(key))

  def filterKeys(keys: Set[String]): Json => Json =
    keys.map { root.at(_).replace(None) }.reduce(_ andThen _)

  def filterNestedKeys(keys: String*): Json => Json = {
    def inner(jsonObject: JsonObject): JsonObject = {
      val filtered = keys.foldLeft(jsonObject) { (o, k) => o.remove(k) }
      JsonObject.fromIterable(
        filtered.toList.map { case (k, v) =>
          v.arrayOrObject(
            k -> v,
            a =>
              k -> Json.fromValues(
                a.map { element =>
                  element.asObject.fold(element) { e => Json.fromJsonObject(inner(e)) }
                }
              ),
            o => k -> Json.fromJsonObject(inner(o))
          )
        }
      )
    }
    root.obj.modify(inner)
  }

  private val realmKeysToIgnore     = Set("_createdAt", "_createdBy", "_updatedAt", "_updatedBy", "_grantTypes")
  val filterRealmKeys: Json => Json = filterKeys(realmKeysToIgnore)

  private val metadataKeys             = Set("_uuid", "_createdAt", "_updatedAt", "_organizationUuid")
  val filterMetadataKeys: Json => Json = filterKeys(metadataKeys)

  private val projectKeysToIgnore             = metadataKeys + "_effectiveApiMappings"
  val filterProjectMetadataKeys: Json => Json = filterKeys(projectKeysToIgnore)

  def filterResults(keys: Set[String]): Json => Json = root._results.arr.modify(_.map(filterKeys(keys)))

  val filterResultMetadata: Json => Json = filterResults(metadataKeys)

  val filterSearchMetadata: Json => Json = filterKey("_next") andThen filterResultMetadata

  private val linkKeys                           = Set("_self")
  val filterResultMetadataAndLinks: Json => Json = filterResults(metadataKeys ++ linkKeys)
  val filterSearchMetadataAndLinks: Json => Json = filterKey("_next") andThen filterResultMetadataAndLinks

  val `@id`   = root.`@id`.string
  val `@type` = root.`@type`.string
  val context = root.`@context`.json
  val _uuid   = root._uuid.string
  val _rev    = root._rev.int

  val _total = root._total.long

  val hits        = root.hits.hits
  val totalHits   = root.hits.total.value.int
  val hitsSource  = hits.each._source.json
  def hitsIds     = hits.each._source.`@id`.string
  def hitProjects = hits.each._source._project.string

  val location = root._location.string

  object admin {
    val `@type` = root.`@type`.string

    val _label             = root._label.string
    val description        = root.description.string
    val _constrainedBy     = root._constrainedBy.string
    val _rev               = root._rev.int
    val _deprecated        = root._deprecated.boolean
    val _markedForDeletion = root._markedForDeletion.boolean

    val progress  = root.progress.string
    val _finished = root._finished.boolean

    val base                 = root.base.string
    val vocab                = root.vocab.string
    val apiMappings          = root.apiMappings.arr.to {
      extractApiMappings("prefix", "namespace")
    }
    val effectiveApiMappings = root._effectiveApiMappings.arr.to {
      extractApiMappings("_prefix", "_namespace")
    }
    val enforceSchema        = root.enforceSchema.boolean

    private def extractApiMappings(prefixKey: String, namespaceKey: String)(raw: Vector[Json]) =
      raw.mapFilter { entryJson =>
        for {
          entry     <- entryJson.asObject
          prefix    <- entry(prefixKey).flatMap(_.asString)
          namespace <- entry(namespaceKey).flatMap(_.asString)
        } yield prefix -> namespace
      }.toMap

  }

  object keycloak {
    val access_token = root.access_token.string
  }

  object error {
    val `@type`             = root.`@type`.string
    val deleteErrorMessages = root.obj.modify(_.remove("reason").remove("details"))
  }

  object listing {
    val _next      = root._next.string
    val eachResult = root._results.each
    val _results   = root._results.arr
    val _total     = root._total.long
  }

  val projections = root.projections.each

  object events {
    val filterFields = filterKeys(Set("_instant", "_updatedAt"))
      .andThen(
        List("_location", "_uuid", "_path")
          .map { root._attributes.at(_).replace(None) }
          .reduce(_ andThen _)
      )
  }

  object sparql {

    val countResult: Json => Option[Int] = root.results.bindings(0).count.value.string.getOption(_).map(_.toInt)

  }

  object supervision {

    /**
      * Getting the projects related to the running projections
      */
    val allProjects = root.projections.each.metadata.project
  }

}
