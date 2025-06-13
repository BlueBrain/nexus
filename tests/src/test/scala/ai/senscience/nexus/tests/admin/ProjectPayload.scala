package ai.senscience.nexus.tests.admin

import ai.senscience.nexus.tests.config.TestsConfig
import ch.epfl.bluebrain.nexus.testkit.Generators
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}

final case class ProjectPayload(
    description: String,
    base: String,
    vocab: Option[String],
    apiMappings: Map[String, String],
    enforceSchema: Boolean
)

object ProjectPayload extends Generators {

  def baseForProject(name: String)(implicit config: TestsConfig) = s"${config.deltaUri.toString()}/resources/$name/_/"

  def generate(name: String, enforceSchema: Boolean = false)(implicit config: TestsConfig): ProjectPayload = {
    val base = baseForProject(name)
    generateWithCustomBase(name, base, enforceSchema)
  }

  def generateWithCustomBase(name: String, base: String, enforceSchema: Boolean = false): ProjectPayload =
    ProjectPayload(
      description = s"$name project",
      base = base,
      vocab = None,
      apiMappings = Map(
        "attachment"    -> base,
        "nxv"           -> "https://bluebrain.github.io/nexus/vocabulary/",
        "test-schema"   -> "https://dev.nexus.test.com/test-schema",
        "project"       -> "http://admin.dev.nexus.ocp.bbp.epfl.ch/v1/static/schemas/project",
        "test-resource" -> "https://dev.nexus.test.com/simplified-resource/",
        "test-resolver" -> "http://localhost/resolver",
        "patchedcell"   -> "https://bbp.epfl.ch/nexus/v0/data/bbp/experiment/patchedcell/v0.1.0/"
      ),
      enforceSchema = enforceSchema
    )

  def generateBbp(name: String, enforceSchema: Boolean = false): ProjectPayload =
    ProjectPayload(
      description = s"$name project",
      base = "https://bbp.epfl.ch/neurosciencegraph/data/",
      vocab = Some("https://bbp.epfl.ch/ontologies/core/bmo/"),
      apiMappings = Map(
        "datashapes"       -> "https://neuroshapes.org/dash/",
        "prov"             -> "http://www.w3.org/ns/prov#",
        "ontologies"       -> "https://dev.nexus.test.com/test-schema",
        "context"          -> "https://incf.github.io/neuroshapes/contexts/",
        "provcommonshapes" -> "https://provshapes.org/commons/",
        "commonshapes"     -> "https://neuroshapes.org/commons/",
        "provdatashapes"   -> "https://provshapes.org/datashapes/",
        "taxonomies"       -> "https://neuroshapes.org/dash/taxonomy",
        "schemaorg"        -> "http://schema.org/"
      ),
      enforceSchema = enforceSchema
    )

  implicit val encoder: Encoder[ProjectPayload] = Encoder.AsObject.instance { payload =>
    val mappings   = Json.arr(
      payload.apiMappings.map { case (prefix, namespace) =>
        Json.obj("prefix" := prefix, "namespace" := namespace)
      }.toSeq*
    )
    JsonObject(
      "description"   := payload.description,
      "base"          := payload.base,
      "vocab"         := payload.vocab,
      "apiMappings"   -> mappings,
      "enforceSchema" := payload.enforceSchema
    )
  }

}
