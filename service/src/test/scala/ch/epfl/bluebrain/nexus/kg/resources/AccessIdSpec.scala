package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.urlEncode
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Contexts
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AccessIdSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelper {

  "An AccessId" should {
    implicit val http                                  = HttpConfig("http://resources.nexus.com", 80, "v1", "http://resources.nexus.com")
    val defaultPrefixMapping: Map[String, AbsoluteIri] = Map(
      "nxv"           -> nxv.base,
      "nxs"           -> base,
      "nxc"           -> Contexts.base,
      "resource"      -> unconstrainedSchemaUri,
      "elasticsearch" -> nxv.defaultElasticSearchIndex.value,
      "sparql"        -> nxv.defaultSparqlIndex.value
    )
    val mappings                                       = Map("test-schema" -> url"http://schemas.nexus.example.com/test/v0.1.0/") ++ defaultPrefixMapping
    val uuid                                           = UUID.fromString("20fdc0fc-841a-11e8-adc0-fa7ae01bbebc")
    // format: off
    implicit val projectMeta = ResourceF(genIri, uuid, 0L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Project("core", genUUID, "bbp", None, mappings, url"http://unused.com/", genIri))
    // format: on

    "generate short access id" in {
      val list = List(
        (
          url"http://example.com/a",
          shaclSchemaUri,
          s"http://resources.nexus.com/v1/schemas/bbp/core/${urlEncode("http://example.com/a")}"
        ),
        (
          url"http://unused.com/",
          unconstrainedSchemaUri,
          s"http://resources.nexus.com/v1/resources/bbp/core/_/${urlEncode("http://unused.com/")}"
        ),
        (
          url"http://example.com/a",
          fileSchemaUri,
          s"http://resources.nexus.com/v1/files/bbp/core/${urlEncode("http://example.com/a")}"
        ),
        (
          url"http://example.com/a",
          storageSchemaUri,
          s"http://resources.nexus.com/v1/storages/bbp/core/${urlEncode("http://example.com/a")}"
        ),
        (
          url"http://schemas.nexus.example.com/test/v0.1.0/a",
          unconstrainedSchemaUri,
          s"http://resources.nexus.com/v1/resources/bbp/core/_/test-schema:a"
        ),
        (
          url"${base.asString}b",
          url"http://example.com/a",
          s"http://resources.nexus.com/v1/resources/bbp/core/${urlEncode("http://example.com/a")}/nxs:b"
        ),
        (
          url"https://bluebrain.github.io/nexus/schemas/some/other",
          url"http://example.com/a",
          s"http://resources.nexus.com/v1/resources/bbp/core/${urlEncode("http://example.com/a")}/nxs:some%2Fother"
        ),
        (
          url"http://unused.com/something/uuid",
          unconstrainedSchemaUri,
          s"http://resources.nexus.com/v1/resources/bbp/core/_/${urlEncode("something/uuid")}"
        )
      )
      forAll(list) {
        case (id, schemaId, result) =>
          AccessId(id, schemaId).asString shouldEqual result
      }
    }

    "generate long access id" in {
      val list = List(
        (
          url"http://example.com/a",
          shaclSchemaUri,
          s"http://resources.nexus.com/v1/schemas/bbp/core/${urlEncode("http://example.com/a")}"
        ),
        (
          url"http://example.com/a",
          fileSchemaUri,
          s"http://resources.nexus.com/v1/files/bbp/core/${urlEncode("http://example.com/a")}"
        ),
        (
          url"http://example.com/a",
          storageSchemaUri,
          s"http://resources.nexus.com/v1/storages/bbp/core/${urlEncode("http://example.com/a")}"
        ),
        (
          url"http://schemas.nexus.example.com/test/v0.1.0/a",
          unconstrainedSchemaUri,
          s"http://resources.nexus.com/v1/resources/bbp/core/_/${urlEncode("http://schemas.nexus.example.com/test/v0.1.0/a")}"
        ),
        (
          url"${base.asString}b",
          url"http://example.com/a",
          s"http://resources.nexus.com/v1/resources/bbp/core/${urlEncode("http://example.com/a")}/${urlEncode(s"${base.asString}b")}"
        ),
        (
          url"https://bluebrain.github.io/nexus/schemas/some/other",
          url"http://example.com/a",
          s"http://resources.nexus.com/v1/resources/bbp/core/${urlEncode("http://example.com/a")}/${urlEncode("https://bluebrain.github.io/nexus/schemas/some/other")}"
        ),
        (
          url"http://unused.com/something/uuid",
          unconstrainedSchemaUri,
          s"http://resources.nexus.com/v1/resources/bbp/core/_/${urlEncode("http://unused.com/something/uuid")}"
        )
      )
      forAll(list) {
        case (id, schemaId, result) =>
          AccessId(id, schemaId, expanded = true).asString shouldEqual result
      }
    }
  }

}
