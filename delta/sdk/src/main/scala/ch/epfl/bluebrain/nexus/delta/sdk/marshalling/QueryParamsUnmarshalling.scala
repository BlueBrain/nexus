package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.{IriBase, IriVocab}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label, TagLabel}

/**
  * Unmarshallers from String to ''A''
  */
trait QueryParamsUnmarshalling {

  /**
    * Unmarsaller to transform a String to Iri
    */
  implicit def iriFromStringUnmarshaller: FromStringUnmarshaller[Iri] =
    Unmarshaller.strict[String, Iri] { string =>
      Iri(string) match {
        case Right(iri) => iri
        case Left(err)  => throw new IllegalArgumentException(err)
      }
    }

  /**
    * Unmarsaller to transform a String to Label
    */
  implicit def labelFromStringUnmarshaller: FromStringUnmarshaller[Label] =
    Unmarshaller.strict[String, Label] { string =>
      Label(string) match {
        case Right(iri) => iri
        case Left(err)  => throw new IllegalArgumentException(err.getMessage)
      }
    }

  /**
    * Unmarsaller to transform a String to TagLabel
    */
  implicit def tagLabelFromStringUnmarshaller: FromStringUnmarshaller[TagLabel] =
    Unmarshaller.strict[String, TagLabel] { string =>
      TagLabel(string) match {
        case Right(tagLabel) => tagLabel
        case Left(err)       => throw new IllegalArgumentException(err.getMessage)
      }
    }

  /**
    * Unmarsaller to transform an Iri to a Subject
    */
  implicit def subjectFromIriUnmarshaller(implicit base: BaseUri): Unmarshaller[Iri, Subject] =
    Unmarshaller.strict[Iri, Subject] { iri =>
      Subject.unsafe(iri) match {
        case Right(subject) => subject
        case Left(err)      => throw new IllegalArgumentException(err.getMessage)
      }
    }

  /**
    * Unmarsaller to transform a String to a Subject
    */
  implicit def subjectFromStringUnmarshaller(implicit base: BaseUri): FromStringUnmarshaller[Subject] =
    iriFromStringUnmarshaller.andThen(subjectFromIriUnmarshaller)

  /**
    * Unmarsaller to transform a String to an IriVocab
    */
  implicit def iriVocabFromStringUnmarshaller(implicit project: Project): FromStringUnmarshaller[IriVocab] =
    iriFromStringUnmarshaller(useVocab = true).map(IriVocab)

  /**
    * Unmarsaller to transform a String to an IriBase
    */
  implicit def iriBaseFromStringUnmarshaller(implicit project: Project): FromStringUnmarshaller[IriBase] =
    iriFromStringUnmarshaller(useVocab = false).map(IriBase)

  private def iriFromStringUnmarshaller(useVocab: Boolean)(implicit project: Project): FromStringUnmarshaller[Iri] =
    Unmarshaller.strict[String, Iri] { str =>
      val ctx = context(project.vocab, project.base.iri, project.apiMappings)
      ctx.expand(str, useVocab = useVocab) match {
        case Some(iri) => iri
        case None      => throw new IllegalArgumentException(s"'$str' cannot be expanded to an Iri")

      }
    }

  /**
    * Unmarsaller to transform a String to an IdSegment
    */
  implicit val idSegmentFromStringUnmarshaller: FromStringUnmarshaller[IdSegment] =
    Unmarshaller.strict[String, IdSegment](IdSegment.apply)

  private def context(vocab: Iri, base: Iri, mappings: ApiMappings): JsonLdContext =
    JsonLdContext(
      ContextValue.empty,
      base = Some(base),
      vocab = Some(vocab),
      prefixMappings = mappings.prefixMappings,
      aliases = mappings.aliases
    )

}

object QueryParamsUnmarshalling extends QueryParamsUnmarshalling {

  /**
    * An Iri generated using the vocab when there is no alias or curie suited for it
    */
  final case class IriVocab private[sdk] (value: Iri) extends AnyVal

  /**
    * An Iri generated using the base when there is no alias or curie suited for it
    */
  final case class IriBase private[sdk] (value: Iri) extends AnyVal
}
