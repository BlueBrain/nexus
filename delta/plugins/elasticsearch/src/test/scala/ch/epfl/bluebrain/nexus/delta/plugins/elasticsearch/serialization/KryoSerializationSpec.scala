package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.serialization

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.serialization.SerializationExtension
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultElasticsearchSettings, permissions}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import com.typesafe.config.ConfigFactory
import io.altoo.akka.serialization.kryo.KryoSerializer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, TryValues}

class KryoSerializationSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load("akka-test.conf"))
    with AnyWordSpecLike
    with Matchers
    with TryValues
    with TestHelpers
    with IOValues
    with EitherValuable
    with CirceLiteral
    with Inspectors {

  private val serialization = SerializationExtension(system)

  private val project = ProjectRef.unsafe("org", "proj")

  private val defaultEsSettings = defaultElasticsearchSettings.accepted

  private val indexingValue = IndexingElasticSearchViewValue(
    resourceSchemas = Set.empty,
    resourceTypes = Set(nxv + "Type1", nxv + "Type2"),
    resourceTag = None,
    mapping = jobj"""{"properties": {"@type": {"type": "keyword"}, "@id": {"type": "keyword"} } }""",
    settings = defaultEsSettings,
    includeMetadata = true,
    includeDeprecated = true,
    sourceAsText = true,
    permission = permissions.query
  )
  private val aggValue      = AggregateElasticSearchViewValue(
    NonEmptySet.of(ViewRef(project, nxv + "id1"), ViewRef(project, nxv + "id2"))
  )

  "A ElasticSearchViewValue Kryo serialization" should {
    "succeed" in {
      forAll(List(indexingValue, aggValue)) { view =>
        // Find the Serializer for it
        val serializer = serialization.findSerializerFor(view)
        serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

        // Check serialization/deserialization
        val serialized = serialization.serialize(view)
        serialized.isSuccess shouldEqual true

        val deserialized = serialization.deserialize(serialized.get, view.getClass)
        deserialized.isSuccess shouldEqual true
        deserialized.success.value shouldEqual view
      }
    }
  }
}
