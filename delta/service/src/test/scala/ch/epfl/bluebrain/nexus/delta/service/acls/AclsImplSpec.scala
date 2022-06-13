package ch.epfl.bluebrain.nexus.delta.service.acls

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import monix.bio.{Task, UIO}
import org.scalatest.Inspectors

import java.util.UUID

class AclsImplSpec extends AbstractDBSpec with AclsBehaviors with Inspectors with CirceLiteral with ConfigFixtures {

  implicit lazy val uuidF: UUIDF = UUIDF.fixed(UUID.randomUUID())

  private def eventLog: Task[EventLog[Envelope[AclEvent]]] =
    EventLog.postgresEventLog(EventLogUtils.toEnvelope)

  override def create: Task[Acls] =
    for {
      el  <- eventLog
      c    = AclsConfig(aggregate, keyValueStore, cacheIndexing)
      r   <- RealmSetup.init(realm, realm2)
      agg <- AclsImpl.aggregate(UIO.pure(minimumPermissions), r, aggregate)
      a   <- AclsImpl(
               agg,
               AclsImpl.cache(c),
               c,
               minimumPermissions,
               el
             )
    } yield a

}
