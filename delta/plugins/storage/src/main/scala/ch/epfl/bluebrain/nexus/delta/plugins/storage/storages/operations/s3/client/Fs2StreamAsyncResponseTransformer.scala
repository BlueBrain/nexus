package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import org.reactivestreams.Publisher
import software.amazon.awssdk.core.async.{AsyncResponseTransformer, SdkPublisher}
import software.amazon.awssdk.services.s3.model.GetObjectResponse

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

private[client] class Fs2StreamAsyncResponseTransformer
    extends AsyncResponseTransformer[GetObjectResponse, Publisher[ByteBuffer]] {

  private val cf                                                   = new CompletableFuture[Publisher[ByteBuffer]]()
  override def prepare(): CompletableFuture[Publisher[ByteBuffer]] = cf

  override def onResponse(response: GetObjectResponse): Unit = ()

  override def onStream(publisher: SdkPublisher[ByteBuffer]): Unit = {
    cf.complete(publisher)
    ()
  }

  override def exceptionOccurred(error: Throwable): Unit = {
    cf.completeExceptionally(error)
    ()
  }
}
