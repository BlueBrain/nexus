package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.SourceType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * A source for [[CompositeViewValue]].
  */
sealed trait CompositeViewSource extends Product with Serializable {

  /**
    * @return the id of the source.
    */
  def id: Iri

  /**
    * @return the set of schemas considered for indexing; empty implies all
    */
  def resourceSchemas: Set[Iri]

  /**
    * @return the set of resource types considered for indexing; empty implies all
    */
  def resourceTypes: Set[Iri]

  /**
    * @return an optional tag to consider for indexing; when set, all resources that are tagged with
    * the value of the field are indexed with the corresponding revision
    */
  def resourceTag: Option[TagLabel]

  /**
    * @return whether to consider deprecated resources for indexing
    */
  def includeDeprecated: Boolean

  /**
    * @return the type of the source
    */
  def tpe: SourceType
}

object CompositeViewSource {

  /**
    * A source for the current project.
    *
    * @param id                 the id of the source.
    * @param resourceSchemas    the set of schemas considered for indexing; empty implies all
    * @param resourceTypes      the set of resource types considered for indexing; empty implies all
    * @param resourceTag        an optional tag to consider for indexing; when set, all resources that are tagged with
    *                           the value of the field are indexed with the corresponding revision
    * @param includeDeprecated  whether to consider deprecated resources for indexing
    */
  final case class ProjectSource(
      id: Iri,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeDeprecated: Boolean
  ) extends CompositeViewSource {

    override def tpe: SourceType = ProjectSourceType
  }

  /**
    * A cross project source.
    *
    * @param id                 the id of the source.
    * @param resourceSchemas    the set of schemas considered for indexing; empty implies all
    * @param resourceTypes      the set of resource types considered for indexing; empty implies all
    * @param resourceTag        an optional tag to consider for indexing; when set, all resources that are tagged with
    *                           the value of the field are indexed with the corresponding revision
    * @param includeDeprecated  whether to consider deprecated resources for indexing
    * @param project            the project to which source refers to
    * @param identities         the identities used to access the project
    */
  final case class CrossProjectSource(
      id: Iri,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeDeprecated: Boolean,
      project: ProjectRef,
      identities: Set[Identity]
  ) extends CompositeViewSource {

    override def tpe: SourceType = CrossProjectSourceType
  }

  /**
    * A remote project source
    *
    * @param id                 the id of the source
    * @param resourceSchemas    the set of schemas considered for indexing; empty implies all
    * @param resourceTypes      the set of resource types considered for indexing; empty implies all
    * @param resourceTag        an optional tag to consider for indexing; when set, all resources that are tagged with
    *                           the value of the field are indexed with the corresponding revision
    * @param includeDeprecated  whether to consider deprecated resources for indexing
    * @param endpoint           the endpoint used to access the source
    * @param token              the optional access token used to connect to the endpoint
    */
  final case class RemoteProjectSource(
      id: Iri,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeDeprecated: Boolean,
      project: ProjectRef,
      endpoint: Uri,
      token: Option[AccessToken]
  ) extends CompositeViewSource {

    override def tpe: SourceType = RemoteProjectSourceType
  }

  final case class AccessToken(value: Secret[String])
}
