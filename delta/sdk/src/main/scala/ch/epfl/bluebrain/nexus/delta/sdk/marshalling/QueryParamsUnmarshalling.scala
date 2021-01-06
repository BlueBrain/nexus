package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import akka.http.scaladsl.unmarshalling.FromStringUnmarshaller

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

}

object QueryParamsUnmarshalling extends QueryParamsUnmarshalling
