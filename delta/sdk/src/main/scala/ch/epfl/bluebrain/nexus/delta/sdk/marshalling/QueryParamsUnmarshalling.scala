package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.{IriBase, IriVocab}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.StoragePermissionProvider.AccessType
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json
import io.circe.parser.parse

/**
  * Unmarshallers from String to ''A''
  */
trait QueryParamsUnmarshalling {

  /**
    * Unmarshaller to transform a String to Iri
    */
  implicit val iriFromStringUnmarshaller: FromStringUnmarshaller[Iri] =
    Unmarshaller.strict[String, Iri] { string =>
      Iri(string) match {
        case Right(iri) => iri
        case Left(err)  => throw new IllegalArgumentException(err)
      }
    }

  /**
    * Unmarshaller to transform a String to an IriBase
    */
  val iriBaseFromStringUnmarshallerNoExpansion: FromStringUnmarshaller[IriBase] =
    iriFromStringUnmarshaller.map(IriBase)

  /**
    * Unmarsaller to transform a String to an IriVocab
    */
  implicit def iriVocabFromStringUnmarshaller(implicit pc: ProjectContext): FromStringUnmarshaller[IriVocab] =
    expandIriFromStringUnmarshaller(useVocab = true).map(IriVocab.apply)

  /**
    * Unmarshaller to transform a String to an IriBase
    */
  implicit def iriBaseFromStringUnmarshaller(implicit pc: ProjectContext): FromStringUnmarshaller[IriBase] =
    expandIriFromStringUnmarshaller(useVocab = false).map(IriBase)

  private def expandIriFromStringUnmarshaller(
      useVocab: Boolean
  )(implicit pc: ProjectContext): FromStringUnmarshaller[Iri] =
    Unmarshaller.strict[String, Iri] { str =>
      val ctx = context(pc.vocab, pc.base.iri, pc.apiMappings)
      ctx.expand(str, useVocab = useVocab) match {
        case Some(iri) => iri
        case None      => throw new IllegalArgumentException(s"'$str' cannot be expanded to an Iri")

      }
    }

  private def context(vocab: Iri, base: Iri, mappings: ApiMappings): JsonLdContext =
    JsonLdContext(
      ContextValue.empty,
      base = Some(base),
      vocab = Some(vocab),
      prefixMappings = mappings.prefixMappings,
      aliases = mappings.aliases
    )

  /**
    * Unmarshaller to transform a String to Label
    */
  implicit def labelFromStringUnmarshaller: FromStringUnmarshaller[Label] =
    Unmarshaller.strict[String, Label] { string =>
      Label(string) match {
        case Right(iri) => iri
        case Left(err)  => throw new IllegalArgumentException(err.getMessage)
      }
    }

  implicit def projectRefFromStringUnmarshaller: FromStringUnmarshaller[ProjectRef] =
    Unmarshaller.strict[String, ProjectRef] { string =>
      ProjectRef.parse(string) match {
        case Right(iri) => iri
        case Left(err)  => throw new IllegalArgumentException(err)
      }
    }

  /**
    * Unmarshaller to transform a String to TagLabel
    */
  implicit def tagLabelFromStringUnmarshaller: FromStringUnmarshaller[UserTag] =
    Unmarshaller.strict[String, UserTag] { string =>
      UserTag(string) match {
        case Right(tagLabel) => tagLabel
        case Left(err)       => throw new IllegalArgumentException(err.message)
      }
    }

  implicit def permissionFromStringUnmarshaller: FromStringUnmarshaller[Permission] =
    Unmarshaller.strict[String, Permission] { string =>
      Permission(string) match {
        case Right(value) => value
        case Left(err)    => throw new IllegalArgumentException(err.getMessage)
      }
    }

  implicit def accessTypeFromStringUnmarshaller: FromStringUnmarshaller[AccessType] =
    Unmarshaller.strict[String, AccessType] {
      case "read"  => AccessType.Read
      case "write" => AccessType.Write
      case string  =>
        throw new IllegalArgumentException(s"Access type can be either 'read' or 'write', received [$string]")
    }

  /**
    * Unmarshaller to transform an Iri to a Subject
    */
  implicit def subjectFromIriUnmarshaller(implicit base: BaseUri): Unmarshaller[Iri, Subject] =
    Unmarshaller.strict[Iri, Subject] { iri =>
      iri.as[Subject] match {
        case Right(subject) => subject
        case Left(err)      => throw new IllegalArgumentException(err.getMessage)
      }
    }

  /**
    * Unmarshaller to transform a String to a Subject
    */
  implicit def subjectFromStringUnmarshaller(implicit base: BaseUri): FromStringUnmarshaller[Subject] =
    iriFromStringUnmarshaller.andThen(subjectFromIriUnmarshaller)

  /**
    * Unmarshaller to transform a String to an IdSegment
    */
  implicit val idSegmentFromStringUnmarshaller: FromStringUnmarshaller[IdSegment] =
    Unmarshaller.strict[String, IdSegment](IdSegment.apply)

  implicit val jsonFromStringUnmarshaller: FromStringUnmarshaller[Json] =
    Unmarshaller.strict[String, Json](parse(_).fold(throw _, identity))

}

object QueryParamsUnmarshalling extends QueryParamsUnmarshalling {

  /**
    * An Iri generated using the vocab when there is no alias or curie suited for it
    */
  final case class IriVocab private[sdk] (value: Iri) extends AnyVal

  /**
    * An Iri generated using the base when there is no alias or curie suited for it
    */
  final case class IriBase(value: Iri) extends AnyVal
}
