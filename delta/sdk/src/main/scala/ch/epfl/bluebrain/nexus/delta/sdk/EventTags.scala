package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.{OrganizationScopedEvent, ProjectScopedEvent, UnScopedEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

import scala.annotation.unused

/**
  * Provides the tags for certain events
  */
object EventTags {

  /**
    * Creates event log tag for this organization.
    */
  def orgTag(org: Label): String = s"${Organizations.entityType}=$org"

  /**
    * Creates event log tag for this organization and a specific moduleType.
    */
  def orgTag(moduleType: String, org: Label): String = s"$moduleType-${Organizations.entityType}=$org"

  /**
    * @return
    *   the tags for [[ResourceEvent]] s
    */
  def forResourceEvents(moduleType: String)(ev: ResourceEvent): Set[String] =
    Set(
      Event.eventTag,
      moduleType,
      Projects.projectTag(ev.project),
      orgTag(ev.project.organization)
    )

  /**
    * @return
    *   the tags for [[ProjectScopedEvent]] s
    */
  def forProjectScopedEvent[E <: ProjectScopedEvent](moduleTypes: String*)(ev: E): Set[String] =
    forOrganizationScopedEvent(moduleTypes: _*)(ev) ++
      moduleTypes.map(moduleType => Projects.projectTag(moduleType, ev.project)).toSet +
      Projects.projectTag(ev.project)

  /**
    * @return
    *   the tags for [[OrganizationScopedEvent]] s
    */
  def forOrganizationScopedEvent[E <: OrganizationScopedEvent](moduleTypes: String*)(ev: E): Set[String] =
    moduleTypes.toSet ++
      Set(Event.eventTag, orgTag(ev.organizationLabel)) ++
      moduleTypes.map(moduleType => orgTag(moduleType, ev.organizationLabel)).toSet

  /**
    * @return
    *   the tags for [[UnScopedEvent]] s
    */
  def forUnScopedEvent[E <: UnScopedEvent](moduleType: String)(@unused ev: E): Set[String] =
    Set(Event.eventTag, moduleType)
}
