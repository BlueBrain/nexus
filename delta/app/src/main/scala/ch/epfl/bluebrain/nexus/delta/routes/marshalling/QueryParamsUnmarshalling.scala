package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

trait QueryParamsUnmarshalling {

  implicit def iriFromStringUnmarshaller: Unmarshaller[String, Iri] =
    Unmarshaller.strict[String, Iri] { string =>
      Iri(string) match {
        case Right(iri) => iri
        case Left(err)  => throw new IllegalArgumentException(err)
      }
    }

  implicit def subjectFromIriUnmarshaller(implicit base: BaseUri): Unmarshaller[Iri, Subject] =
    Unmarshaller.strict[Iri, Subject] { iri =>
      Subject.unsafe(iri) match {
        case Right(subject) => subject
        case Left(err)      => throw new IllegalArgumentException(err.getMessage)
      }
    }

  implicit def subjectFromStringUnmarshaller(implicit base: BaseUri): Unmarshaller[String, Subject] =
    iriFromStringUnmarshaller.andThen(subjectFromIriUnmarshaller)

}

object QueryParamsUnmarshalling extends QueryParamsUnmarshalling
