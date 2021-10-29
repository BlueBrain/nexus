package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingDataGen
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.Pipe.{excludeDeprecated, excludeMetadata, sourceAsText, validate, withoutConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeError.{InvalidConfig, PipeNotFound}
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import monix.bio.Task
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._

import scala.collection.mutable

class PipeSpec extends AnyWordSpec with TestHelpers with IOValues with Matchers with OptionValues with EitherValuable {

  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val res: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> ContextValue.fromFile("contexts/metadata.json").accepted
  )

  val recorded: mutable.Seq[Iri] = mutable.Seq()

  val recorder: Pipe = withoutConfig("recorder", d => Task.delay(recorded.appended(d.id)).as(Some(d)))

  val error            = new IllegalArgumentException("Fail !!!")
  val alwaysFail: Pipe = withoutConfig("alwaysFail", _ => Task.raiseError(error))

  val availablePipes: Map[String, Pipe] = List(excludeMetadata, excludeDeprecated, recorder, alwaysFail).map { p =>
    p.name -> p
  }.toMap

  private val project = ProjectRef.unsafe("org", "proj")

  private val source = jsonContentOf("resource/source.json")

  private val data = IndexingDataGen
    .fromDataResource(
      nxv + "id",
      project,
      source
    )
    .accepted

  "Exclude metadata" should {
    "remove all metadata" in {
      excludeMetadata.parseAndRun(None, data).accepted.value shouldEqual data.copy(metadataGraph = Graph.empty)
    }
  }

  "Exclude deprecated" should {

    "not modify non-deprecated data" in {
      excludeDeprecated.parseAndRun(None, data).accepted.value shouldEqual data
    }

    "filter out deprecated data" in {
      excludeDeprecated.parseAndRun(None, data.copy(deprecated = true)).accepted shouldEqual None
    }
  }

  "Source as test" should {
    "add source as a field in the graph" in {
      sourceAsText.parseAndRun(None, data).accepted.value shouldEqual data.copy(graph =
        data.graph.add(nxv.originalSource.iri, data.source.noSpaces)
      )
    }
  }

  "Validating pipes" should {
    "succeed if all definitions are valid" in {
      validate(
        PipeDef("excludeDeprecated", None, None) :: PipeDef("excludeMetadata", None, None) :: Nil,
        availablePipes
      ).rightValue
    }

    "fail if a pipe definition references an unknown pipe" in {
      validate(
        PipeDef("excludeDeprecated", None, None) :: PipeDef("xxx", None, None) :: Nil,
        availablePipes
      ).leftValue shouldEqual PipeNotFound("xxx")
    }

    "fail if a pipeline configuration is invalid" in {
      validate(
        PipeDef("excludeDeprecated", None, None) :: PipeDef("excludeMetadata", None, Some(ExpandedJsonLd.empty)) :: Nil,
        availablePipes
      ).leftValue.asInstanceOf[InvalidConfig].name shouldEqual "excludeMetadata"
    }
  }

  "Running pipelines" should {

    "succeed if all definitions are valid" in {
      val result = Pipe
        .run(PipeDef("excludeDeprecated", None, None) :: PipeDef("excludeMetadata", None, None) :: Nil, availablePipes)
        .flatMap(_(data))
        .accepted
      result.value shouldEqual data.copy(metadataGraph = Graph.empty)
    }

    "fail if any of the pipe fail" in {
      val result = Pipe
        .run(
          PipeDef("excludeDeprecated", None, None) :: PipeDef("alwaysFail", None, None) :: PipeDef(
            "recorder",
            None,
            None
          ) :: Nil,
          availablePipes
        )
        .flatMap(_(data))
        .rejected
      result shouldEqual error
    }

    "not attempt to run later pipes if data gets filtered out" in {
      val result = Pipe
        .run(PipeDef("excludeDeprecated", None, None) :: PipeDef("recorder", None, None) :: Nil, availablePipes)
        .flatMap(_(data.copy(deprecated = true)))
        .accepted
      result shouldEqual None
      recorded shouldBe empty
    }

    "fail if a pipe definition references an unknown pipe" in {
      Pipe
        .run(PipeDef("excludeDeprecated", None, None) :: PipeDef("xxx", None, None) :: Nil, availablePipes)
        .rejected shouldEqual PipeNotFound("xxx")
    }

    "fail if a pipeline configuration is invalid" in {
      Pipe
        .run(
          PipeDef("excludeDeprecated", None, None) :: PipeDef(
            "excludeMetadata",
            None,
            Some(ExpandedJsonLd.empty)
          ) :: Nil,
          availablePipes
        )
        .rejectedWith[InvalidConfig]
        .name shouldEqual "excludeMetadata"
    }
  }

}
