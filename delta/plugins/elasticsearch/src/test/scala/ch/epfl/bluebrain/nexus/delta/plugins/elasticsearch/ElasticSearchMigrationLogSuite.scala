package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.ElasticSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant
import java.util.UUID

class ElasticSearchMigrationLogSuite extends BioSuite {

  private val uuid           = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant        = Instant.EPOCH
  private val subject        = User("username", Label.unsafe("myrealm"))
  private val tag            = UserTag.unsafe("mytag")
  private val projectRef     = ProjectRef.unsafe("myorg", "myproj")
  private val indexingValue  = IndexingElasticSearchViewValue(None, None)
  private val indexingSource = indexingValue.toJson _

  // format: off
  private val created = (id: Iri) => ElasticSearchViewCreated(id, projectRef, uuid, indexingValue, indexingSource(id), 1, instant, subject)
  private val updated = (id: Iri) => ElasticSearchViewUpdated(id, projectRef, uuid, indexingValue, indexingSource(id), 2, instant, subject)
  private val tagged = (id: Iri) => ElasticSearchViewTagAdded(id, projectRef, ElasticSearch, uuid, targetRev = 1, tag, 3, instant, subject)
  private val deprecated = (id: Iri) => ElasticSearchViewDeprecated(id, projectRef, ElasticSearch, uuid, 4, instant, subject)
  // format: on 

  private val defaults  = Defaults("esName", "esDescription")
  private val injection = ElasticSearchPluginModule.injectElasticViewDefaults(defaults)

  test("ES view events that need no name/desc injection should stay untouched") {
    val invariantEvents = List(tagged(defaultViewId), deprecated(defaultViewId))
    val injectedEvents  = invariantEvents.map(injection)
    assertEquals(injectedEvents, invariantEvents)
  }

  test("ES view event should not be injected with a name if it's not the default view") {
    val esViewCreatedEvent = created(iri"someId")
    val esViewUpdatedEvent = updated(iri"someId")
    val events             = List(esViewCreatedEvent, esViewUpdatedEvent)
    val injectedEvents     = events.map(injection)
    assertEquals(injectedEvents, events)
  }

  // how the indexing value should look like after the name/desc are injected
  private val expectedIndexingValue =
    IndexingElasticSearchViewValue(Some("esName"), Some("esDescription"))

  test("Default ElasticSearchViewCreated has name/desc injected") {
    val injectedEvent = injection(created(defaultViewId))
    val expected      = ElasticSearchViewCreated(
      defaultViewId,
      projectRef,
      uuid,
      expectedIndexingValue,
      indexingSource(defaultViewId),
      1,
      instant,
      subject
    )
    assertEquals(injectedEvent, expected)
  }

  test("Default ElasticSearchViewUpdated has name/desc injected") {
    val injectedEvent = injection(updated(defaultViewId))
    val expected      = ElasticSearchViewUpdated(
      defaultViewId,
      projectRef,
      uuid,
      expectedIndexingValue,
      indexingSource(defaultViewId),
      2,
      instant,
      subject
    )
    assertEquals(injectedEvent, expected)
  }

}
