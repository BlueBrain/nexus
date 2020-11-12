package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import akka.http.scaladsl.server.ExceptionHandler
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.DeltaDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import monix.execution.Scheduler

object RdfExceptionHandler extends DeltaDirectives {

  /**
    * An [[ExceptionHandler]] that returns RDF output (Json-LD compacted, Json-LD expanded, Dot or NTriples)
    * depending on content negotiation (Accept Header) and ''format'' query parameter
    */
  def apply(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): ExceptionHandler =
    ExceptionHandler { case _: AuthorizationFailed.type =>
      discardEntityAndComplete[ServiceError](AuthorizationFailed)
    }

}
