package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

/**
  * Enumeration of Blazegraph view commands.
  */
sealed trait BlazegraphViewCommand extends Product with Serializable {

  /**
    * @return the view id
    */
  def id: Iri

  /**
    * @return a reference to the parent project
    */
  def project: ProjectRef

  /**
    * @return the identity associated with this command
    */
  def subject: Subject
}

object BlazegraphViewCommand {

  /**
    * Command for creating a new BlazegraphView.
    *
    * @param id       the view id
    * @param project  a reference to the parent project
    * @param value    the view configuration
    * @param source   the original json value provided by the caller
    * @param subject  the identity associated with this command
    */
  final case class CreateBlazegraphView(
      id: Iri,
      project: ProjectRef,
      value: BlazegraphViewValue,
      source: Json,
      subject: Subject
  ) extends BlazegraphViewCommand

  /**
    * Command for the update of a BlazegraphView.
    *
    * @param id       the view id
    * @param project  a reference to the parent project
    * @param value    the view configuration
    * @param rev      the last known revision of the view
    * @param source   the original json value provided by the caller
    * @param subject  the identity associated with this command
    */
  final case class UpdateBlazegraphView(
      id: Iri,
      project: ProjectRef,
      value: BlazegraphViewValue,
      rev: Long,
      source: Json,
      subject: Subject
  ) extends BlazegraphViewCommand

  /**
    * Command for the deprecation of a BlazegraphView.
    *
    * @param id       the view id
    * @param project  a reference to the parent project
    * @param rev      the last known revision of the view
    * @param subject  the identity associated with this command
    */
  final case class DeprecateBlazegraphView(id: Iri, project: ProjectRef, rev: Long, subject: Subject)
      extends BlazegraphViewCommand

  /**
    * Command for adding a tag to a Blazegraph view.
    *
    * @param id        the view id
    * @param project   a reference to the parent project
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag label
    * @param rev       the last known revision of the view
    * @param subject   the identity associated with this command
    */
  final case class TagBlazegraphView(
      id: Iri,
      project: ProjectRef,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      subject: Subject
  ) extends BlazegraphViewCommand
}
