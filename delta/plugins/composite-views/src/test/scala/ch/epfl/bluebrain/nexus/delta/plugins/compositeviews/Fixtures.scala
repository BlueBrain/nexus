package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues

trait Fixtures extends IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    iri"http://music.com/context"   -> ContextValue.fromFile("indexing/music-context.json").accepted,
    contexts.compositeViews         -> ContextValue.fromFile("contexts/composite-views.json").accepted,
    contexts.compositeViewsMetadata -> ContextValue.fromFile("contexts/composite-views-metadata.json").accepted,
    Vocabulary.contexts.metadata    -> ContextValue.fromFile("contexts/metadata.json").accepted,
    Vocabulary.contexts.error       -> ContextValue.fromFile("contexts/error.json").accepted,
    Vocabulary.contexts.shacl       -> ContextValue.fromFile("contexts/shacl.json").accepted,
    Vocabulary.contexts.statistics  -> ContextValue.fromFile("/contexts/statistics.json").accepted,
    Vocabulary.contexts.offset      -> ContextValue.fromFile("/contexts/offset.json").accepted,
    Vocabulary.contexts.tags        -> ContextValue.fromFile("contexts/tags.json").accepted,
    Vocabulary.contexts.search      -> ContextValue.fromFile("contexts/search.json").accepted
  )
}
