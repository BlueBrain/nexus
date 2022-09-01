package ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.MultiDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{StateStreaming, UniformScopedState, UniformScopedStateEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.ScopedStateSource.StateSourceConfig
import fs2.Stream
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

/**
  * Source implementation for scoped states.
  * @param id
  *   the id of the source
  * @param cfg
  *   the source configuration
  * @param qc
  *   the query configuration
  * @param xas
  *   the transactor instances
  */
class ScopedStateSource(
    override val id: Iri,
    cfg: StateSourceConfig,
    qc: QueryConfig,
    xas: Transactors
)(implicit md: MultiDecoder[UniformScopedState])
    extends Source {

  override type Out = UniformScopedState
  override def label: Label                          = ScopedStateSource.label
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(offset: ProjectionOffset): Stream[Task, Elem[UniformScopedState]] =
    StateStreaming.scopedStates(cfg.project, cfg.tag, offset.forSource(this), qc, xas).map { envelope =>
      SuccessElem(
        ctx = ElemCtx.SourceId(id),
        tpe = envelope.tpe,
        id = envelope.value.id,
        rev = envelope.rev,
        instant = envelope.instant,
        offset = envelope.offset,
        value = envelope.value
      )
    }
}

/**
  * Source implementation for scoped states.
  */
object ScopedStateSource {

  final val label: Label = Label.unsafe("scoped-state-source")

  def apply(
      qc: QueryConfig,
      xas: Transactors,
      uniformScopedStateEncoders: Set[UniformScopedStateEncoder[_]]
  ): ScopedStateSourceDef = {
    val md = MultiDecoder(uniformScopedStateEncoders.map(e => e.entityType -> e.uniformScopedDecoder).toMap)
    new ScopedStateSourceDef(qc, xas)(md)
  }

  class ScopedStateSourceDef(
      qc: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[UniformScopedState])
      extends SourceDef {
    override type SourceType = ScopedStateSource
    override type Config     = StateSourceConfig
    override def configType: Typeable[StateSourceConfig]         = Typeable[StateSourceConfig]
    override def configDecoder: JsonLdDecoder[StateSourceConfig] = StateSourceConfig.stateSourceConfigJsonLdDecoder
    override val label: Label                                    = ScopedStateSource.label

    override def withConfig(config: StateSourceConfig, id: Iri): ScopedStateSource =
      new ScopedStateSource(id, config, qc, xas)
  }

  final case class StateSourceConfig(project: ProjectRef, tag: Tag) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          BNode.random,
          JsonObject(
            (nxv + "project").toString -> Json.arr(Json.obj("@value" -> Json.fromString(project.toString))),
            (nxv + "tag").toString     -> Json.arr(Json.obj("@value" -> tag.asJson))
          )
        )
      )
    )
  }
  object StateSourceConfig                                          {
    implicit val stateSourceConfigJsonLdDecoder: JsonLdDecoder[StateSourceConfig] = deriveJsonLdDecoder
  }
}
