package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.Pipe._
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeError.{InvalidConfig, PipeNotFound}
import monix.bio.Task

import scala.collection.mutable

class PipeSpec extends PipeBaseSpec {

  val recorded: mutable.Seq[Iri] = mutable.Seq()

  val recorder: Pipe       = withoutConfig("recorder", d => Task.delay(recorded.appended(d.id)).as(Some(d)))
  val recorderDef: PipeDef = PipeDef.noConfig("recorder")

  val invalidConfig: PipeDef = PipeDef.withConfig(DiscardMetadata.name, ExpandedJsonLd.empty)
  val unknownPipe: PipeDef   = PipeDef.noConfig("xxx")

  val error                  = new IllegalArgumentException("Fail !!!")
  val alwaysFail: Pipe       = withoutConfig("alwaysFail", _ => Task.raiseError(error))
  val alwaysFailDef: PipeDef = PipeDef.noConfig("alwaysFail")

  val pipeConfig: PipeConfig = PipeConfig(FilterDeprecated.pipe, DiscardMetadata.pipe, recorder, alwaysFail).rightValue

  "Validating pipes" should {
    "succeed if all definitions are valid" in {
      validate(FilterDeprecated() :: DiscardMetadata() :: Nil, pipeConfig).rightValue
    }

    "fail if a pipe definition references an unknown pipe" in {
      validate(
        FilterDeprecated() :: unknownPipe :: Nil,
        pipeConfig
      ).leftValue shouldEqual PipeNotFound("xxx")
    }

    "fail if a pipeline configuration is invalid" in {
      validate(
        FilterDeprecated() :: invalidConfig :: Nil,
        pipeConfig
      ).leftValue.asInstanceOf[InvalidConfig].name shouldEqual "discardMetadata"
    }
  }

  "Running pipelines" ignore {

    "succeed if all definitions are valid" in {
      val result = Pipe
        .run(FilterDeprecated() :: DiscardMetadata() :: Nil, pipeConfig)
        .flatMap(_(sampleData))
        .accepted
      result.value shouldEqual sampleData.copy(metadataGraph = Graph.empty(sampleData.id))
    }

    "fail if any of the pipe fail" in {
      val result = Pipe
        .run(
          FilterDeprecated() :: alwaysFailDef :: recorderDef :: Nil,
          pipeConfig
        )
        .flatMap(_(sampleData))
        .rejected
      result shouldEqual error
    }

    "not attempt to run later pipes if data gets filtered out" in {
      val result = Pipe
        .run(FilterDeprecated() :: recorderDef :: Nil, pipeConfig)
        .flatMap(_(sampleData.copy(deprecated = true)))
        .accepted
      result shouldEqual None
      recorded shouldBe empty
    }

    "fail if a pipe definition references an unknown pipe" in {
      Pipe
        .run(FilterDeprecated() :: unknownPipe :: Nil, pipeConfig)
        .rejected shouldEqual PipeNotFound("xxx")
    }

    "fail if a pipeline configuration is invalid" in {
      Pipe
        .run(FilterDeprecated() :: invalidConfig :: Nil, pipeConfig)
        .rejectedWith[InvalidConfig]
        .name shouldEqual "discardMetadata"
    }
  }

}
