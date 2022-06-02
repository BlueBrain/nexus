package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

/**
  * Enumeration of ElasticSearch view commands.
  */
sealed trait ElasticSearchViewCommand extends Product with Serializable {

  /**
    * @return
    *   the view id
    */
  def id: Iri

  /**
    * @return
    *   a reference to the parent project
    */
  def project: ProjectRef

  /**
    * @return
    *   the identity associated with this command
    */
  def subject: Subject
}

object ElasticSearchViewCommand {

  /**
    * Command for the creation of a new ElasticSearch view.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param value
    *   the view configuration
    * @param source
    *   the original json value provided by the caller
    * @param subject
    *   the identity associated with this command
    */
  final case class CreateElasticSearchView(
      id: Iri,
      project: ProjectRef,
      value: ElasticSearchViewValue,
      source: Json,
      subject: Subject
  ) extends ElasticSearchViewCommand

  /**
    * Command for the update of an ElasticSearch view.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param rev
    *   the last known revision of the view
    * @param value
    *   the view configuration
    * @param source
    *   the original json value provided by the caller
    * @param subject
    *   the identity associated with this command
    */
  final case class UpdateElasticSearchView(
      id: Iri,
      project: ProjectRef,
      rev: Long,
      value: ElasticSearchViewValue,
      source: Json,
      subject: Subject
  ) extends ElasticSearchViewCommand

  /**
    * Command for the deprecation of an ElasticSearch view.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param rev
    *   the last known revision of the view
    * @param subject
    *   the identity associated with this command
    */
  final case class DeprecateElasticSearchView(
      id: Iri,
      project: ProjectRef,
      rev: Long,
      subject: Subject
  ) extends ElasticSearchViewCommand

  /**
    * Command for adding a tag to an ElasticSearch view.
    *
    * @param id
    *   the view id
    * @param project
    *   a reference to the parent project
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag label
    * @param rev
    *   the last known revision of the view
    * @param subject
    *   the identity associated with this command
    */
  final case class TagElasticSearchView(
      id: Iri,
      project: ProjectRef,
      targetRev: Long,
      tag: UserTag,
      rev: Long,
      subject: Subject
  ) extends ElasticSearchViewCommand
}
