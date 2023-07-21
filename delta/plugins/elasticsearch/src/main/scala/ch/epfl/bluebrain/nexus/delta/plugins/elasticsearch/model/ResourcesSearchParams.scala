package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.{Type, TypeOperator}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.{iriFromStringUnmarshaller, iriVocabFromStringUnmarshaller => iriUnmarshaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.TimeRange
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef

/**
  * Search parameters for any generic resource type.
  *
  * @param locate
  *   an [[Iri]] that could be either the id of a resource or its _self
  * @param id
  *   the optional id of the resource
  * @param deprecated
  *   the optional deprecation status of the resource
  * @param rev
  *   the optional revision of the resource
  * @param createdBy
  *   the optional subject who created the resource
  * @param createdAt
  *   the optional time range for resource creation
  * @param updatedBy
  *   the optional subject who last updated the resource
  * @param updatedAt
  *   the optional time range for the last update of the resource
  * @param types
  *   the collection of types to consider, where empty implies all resource types are to be included
  * @param schema
  *   schema to consider, where empty implies any schema
  * @param q
  *   a full text search query parameter
  */
final case class ResourcesSearchParams(
                                        locate: Option[Iri] = None,
                                        id: Option[Iri] = None,
                                        deprecated: Option[Boolean] = None,
                                        rev: Option[Int] = None,
                                        createdBy: Option[Subject] = None,
                                        createdAt: TimeRange = TimeRange.Anytime,
                                        updatedBy: Option[Subject] = None,
                                        updatedAt: TimeRange = TimeRange.Anytime,
                                        types: List[Type] = List.empty,
                                        typeOperator: TypeOperator = TypeOperator.Or,
                                        schema: Option[ResourceRef] = None,
                                        q: Option[String] = None
) {

  /**
    * Adds a schema to the current [[ResourcesSearchParams]] overriding it if necessary
    */
  def withSchema(ref: ResourceRef): ResourcesSearchParams = copy(schema = Some(ref))
}

object ResourcesSearchParams {

  sealed trait TypeOperator extends Product with Serializable

  object TypeOperator {
    case object And extends TypeOperator
    case object Or  extends TypeOperator

    implicit val typeFromStringUnmarshaller: FromStringUnmarshaller[TypeOperator] =
      Unmarshaller.strict[String, TypeOperator] { str =>
        str.toLowerCase() match {
          case "and" => TypeOperator.And
          case "or"  => TypeOperator.Or
          case other => throw new IllegalArgumentException(s"'$other' is not a valid type operator (and/or)")
        }
      }
  }

  /**
    * Enumeration of 'type' search parameters
    */
  sealed trait Type extends Product with Serializable {
    def value: Iri
    def include: Boolean
  }

  object Type {

    /**
      * Type parameter to be included in the search results
      */
    final case class IncludedType(value: Iri) extends Type {
      override val include: Boolean = true
    }

    /**
      * Type parameter to be excluded from the search results
      */
    final case class ExcludedType(value: Iri) extends Type {
      override val include: Boolean = false
    }

    implicit def typeFromStringUnmarshaller(implicit pc: ProjectContext): FromStringUnmarshaller[Type] =
      Unmarshaller.withMaterializer[String, Type](implicit ec =>
        implicit mt => {
          case str if str.startsWith("-") => iriUnmarshaller.apply(str.drop(1)).map(iri => ExcludedType(iri.value))
          case str if str.startsWith("+") => iriUnmarshaller.apply(str.drop(1)).map(iri => IncludedType(iri.value))
          case str                        => iriUnmarshaller.apply(str).map(iri => IncludedType(iri.value))
        }
      )

    def typeFromStringUnmarshallerNoExpansion: FromStringUnmarshaller[Type] =
      Unmarshaller.withMaterializer[String, Type](implicit ec =>
        implicit mt => {
          case str if str.startsWith("-") => iriFromStringUnmarshaller.apply(str.drop(1)).map(ExcludedType)
          case str if str.startsWith("+") => iriFromStringUnmarshaller.apply(str.drop(1)).map(IncludedType)
          case str                        => iriFromStringUnmarshaller.apply(str).map(IncludedType)
        }
      )
  }

}
