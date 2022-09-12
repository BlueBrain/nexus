package ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{StateStreaming, UniformScopedState, UniformScopedStateEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.ScopedStateSource.StateSourceConfig
import com.typesafe.scalalogging.Logger
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
    xas: Transactors,
    decode: (EntityType, Json) => Task[Option[UniformScopedState]]
) extends Source {

  override type Out = UniformScopedState
  override def label: Label                          = ScopedStateSource.label
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(offset: ProjectionOffset): Stream[Task, Elem[UniformScopedState]] =
    StateStreaming
      .scopedStates(cfg.project, cfg.tag, offset.forSource(this), qc, xas, decode)
      .map { envelope =>
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

  final val label: Label   = Label.unsafe("scoped-state-source")
  final val logger: Logger = Logger[ScopedStateSource]

  def apply(
      qc: QueryConfig,
      xas: Transactors,
      uniformScopedStateEncoders: Set[UniformScopedStateEncoder[_]]
  ): ScopedStateSourceDef =
    new ScopedStateSourceDef(qc, xas, uniformScopedStateEncoders)

  class ScopedStateSourceDef(
      qc: QueryConfig,
      xas: Transactors,
      uniformScopedStateEncoders: Set[UniformScopedStateEncoder[_]]
  ) extends SourceDef {
    override type SourceType = ScopedStateSource
    override type Config     = StateSourceConfig
    override def configType: Typeable[StateSourceConfig]         = Typeable[StateSourceConfig]
    override def configDecoder: JsonLdDecoder[StateSourceConfig] = StateSourceConfig.stateSourceConfigJsonLdDecoder
    override val label: Label                                    = ScopedStateSource.label

    override def withConfig(config: StateSourceConfig, id: Iri): ScopedStateSource = {
      val fn: (EntityType, Json) => Task[Option[UniformScopedState]] =
        (tpe, json) =>
          uniformScopedStateEncoders.find(_.entityType == tpe) match {
            case Some(encoder) =>
              encoder
                .decode(json)
                .map(state => Some(state))
                .onErrorHandleWith { df =>
                  Task
                    .delay(logger.error(s"Unable to decode state of type '${tpe.value}' because '${df.getMessage}'"))
                    .as(None)
                }
            case None          =>
              Task
                .delay(logger.warn(s"Unable to find a UniformScopedStateEncoder for entity type '${tpe.value}'"))
                .as(None)
          }

      new ScopedStateSource(id, config, qc, xas, fn)
    }
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
