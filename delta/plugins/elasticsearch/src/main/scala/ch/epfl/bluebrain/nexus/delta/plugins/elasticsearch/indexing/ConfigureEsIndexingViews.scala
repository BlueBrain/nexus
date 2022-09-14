package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.projectionName
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry.LazyReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import shapeless.Typeable

import scala.collection.concurrent

class ConfigureEsIndexingViews(
    registry: ReferenceRegistry,
    supervisor: Supervisor,
    createIndex: ElasticSearchViewState => Task[Unit],
    deleteIndex: ElasticSearchViewState => Task[Unit],
    prefix: String
) extends Pipe {

  private val logger: Logger = Logger[ConfigureEsIndexingViews]

  override type In  = ElasticSearchViewState
  override type Out = Unit
  override def label: Label                             = ConfigureEsIndexingViews.label
  override def inType: Typeable[ElasticSearchViewState] = Typeable[ElasticSearchViewState]
  override def outType: Typeable[Unit]                  = Typeable[Unit]

  private val map = concurrent.TrieMap.empty[(ProjectRef, Iri), ElasticSearchViewState]

  override def apply(element: SuccessElem[ElasticSearchViewState]): Task[Elem[Unit]] = {
    element.value.value match {
      case _: AggregateElasticSearchViewValue => Task.pure(element.dropped)
      case v: IndexingElasticSearchViewValue  =>
        if (element.value.deprecated) cleanUp(element.value).as(element.void)
        else initialise(v, element.value).as(element.void)
    }
  }

  // stop supervision, delete index and wipe projection offset
  private def cleanUp(state: ElasticSearchViewState): Task[Unit] =
    supervisor.unSupervise(projectionName(state), deleteIndex(state), deleteOffset = true)

  // create projection and register in supervision
  private def initialise(value: IndexingElasticSearchViewValue, state: ElasticSearchViewState): Task[Unit] =
    for {
      _ <- Task.when(map.contains(state.project -> state.id))(cleanUp(state))
      _ <- createIndex(state)
      _ <- Indexing.projectionDefFor(value, state, prefix).compile(registry) match {
             case Left(err)       =>
               Task.delay(logger.error(s"Unable to compile projection for view '${state.project}/${state.id}'", err))
             case Right(compiled) =>
               compiled.supervise(supervisor, ExecutionStrategy.SingleNode(persistOffsets = true), createIndex(state))
           }
      _  = map.put(state.project -> state.id, state)
    } yield ()
}

object ConfigureEsIndexingViews {

  val label: Label = Label.unsafe("configure-es-indexing-views")

  def apply(
      registry: LazyReferenceRegistry,
      supervisor: Supervisor,
      createIndex: ElasticSearchViewState => Task[Unit],
      deleteIndex: ElasticSearchViewState => Task[Unit],
      prefix: String
  ): ConfigureESIndexingViewsDef =
    new ConfigureESIndexingViewsDef(registry, supervisor, createIndex, deleteIndex, prefix)

  class ConfigureESIndexingViewsDef(
      registry: LazyReferenceRegistry,
      supervisor: Supervisor,
      createIndex: ElasticSearchViewState => Task[Unit],
      deleteIndex: ElasticSearchViewState => Task[Unit],
      prefix: String
  ) extends PipeDef {
    override type PipeType = ConfigureEsIndexingViews
    override type Config   = Unit
    override def configType: Typeable[Unit]         = Typeable[Unit]
    override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]
    override def label: Label                       = ConfigureEsIndexingViews.label

    override def withConfig(config: Unit): ConfigureEsIndexingViews =
      new ConfigureEsIndexingViews(registry.value, supervisor, createIndex, deleteIndex, prefix)
  }

}
