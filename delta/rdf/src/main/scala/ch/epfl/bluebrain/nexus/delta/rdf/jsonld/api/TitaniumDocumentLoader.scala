package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext
import com.apicatalog.jsonld.document.Document
import com.apicatalog.jsonld.loader.{DocumentLoader, DocumentLoaderOptions}
import com.apicatalog.jsonld.{JsonLdError, JsonLdErrorCode}

import java.net.URI

/**
  * Implementation of Titanium's DocumentLoader for Nexus
  * @param documents
  *   map of loadable documents
  */
final class TitaniumDocumentLoader private (documents: Map[URI, Document]) extends DocumentLoader {

  override def loadDocument(url: URI, options: DocumentLoaderOptions): Document =
    documents.getOrElse(
      url,
      throw new JsonLdError(JsonLdErrorCode.LOADING_DOCUMENT_FAILED, s"Document $url could not be found.")
    )
}

object TitaniumDocumentLoader {

  val empty = new TitaniumDocumentLoader(Map.empty)

  def apply(remoteContexts: Map[Iri, RemoteContext]): TitaniumDocumentLoader = {
    val documents = remoteContexts.map { case (iri, context) =>
      new URI(iri.toString) -> context.value.titaniumDocument
    }
    new TitaniumDocumentLoader(documents)
  }

}
