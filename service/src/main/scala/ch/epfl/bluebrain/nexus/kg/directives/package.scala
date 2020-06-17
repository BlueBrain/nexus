package ch.epfl.bluebrain.nexus.kg

import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.search.Sort
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives.toIri
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

package object directives {

  implicit private[directives] def absoluteIriFromStringUnmarshaller(implicit
      project: Project
  ): Unmarshaller[String, AbsoluteIri] =
    Unmarshaller.strict[String, AbsoluteIri] { string =>
      toIriOrElseBase(string) match {
        case Some(iri) => iri
        case _         => throw new IllegalArgumentException(s"'$string' is not a valid AbsoluteIri value")
      }
    }

  implicit private[directives] def vocabAbsoluteIriFromStringUnmarshaller(implicit
      project: Project
  ): Unmarshaller[String, VocabAbsoluteIri] =
    Unmarshaller.strict[String, VocabAbsoluteIri] { string =>
      toIriOrElseVocab(string) match {
        case Some(iri) => VocabAbsoluteIri(iri)
        case _         => throw new IllegalArgumentException(s"'$string' is not a valid AbsoluteIri value")
      }
    }

  implicit private[directives] val sortFromStringUnmarshaller: Unmarshaller[String, Sort] =
    Unmarshaller.strict[String, Sort](Sort(_))

  private def toIriOrElseBase(s: String)(implicit project: Project): Option[AbsoluteIri] =
    toIri(s) orElse Iri.absolute(project.base.asString + s).toOption

  private def toIriOrElseVocab(s: String)(implicit project: Project): Option[AbsoluteIri] =
    toIri(s) orElse Iri.absolute(project.vocab.asString + s).toOption
}
