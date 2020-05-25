package ch.epfl.bluebrain.nexus.realms

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{NoOffset, PersistenceQuery}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.Monad
import cats.data.EitherT
import cats.effect.{Clock, Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.acls.AclTarget.RootAcl
import ch.epfl.bluebrain.nexus.acls.Acls
import ch.epfl.bluebrain.nexus.auth.Identity.{Anonymous, Authenticated, User}
import ch.epfl.bluebrain.nexus.auth.TokenRejection._
import ch.epfl.bluebrain.nexus.auth.{AccessToken, Caller, Identity, TokenRejection}
import ch.epfl.bluebrain.nexus.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.clients.HttpClient
import ch.epfl.bluebrain.nexus.config.AppConfig.{HttpConfig, RealmsConfig}
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.realms.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.realms.RealmState.{Active, Current, Deprecated, Initial}
import ch.epfl.bluebrain.nexus.realms.Realms.next
import ch.epfl.bluebrain.nexus.routes.SearchParams
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.{AggregateConfig, AkkaAggregate}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, StreamSupervisor}
import ch.epfl.bluebrain.nexus.{ResourceF, ServiceError, TaggingAdapter}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import retry.RetryPolicy

import scala.concurrent.ExecutionContext
import scala.util.Try

/**
  * Realms API.
  *
  * @param agg   the realms aggregate
  * @param acls  a lazy acls api
  * @param index an index implementation for realms
  * @tparam F    the effect type
  */
class Realms[F[_]](val agg: Agg[F], acls: F[Acls[F]], index: RealmIndex[F], groups: Groups[F])(
    implicit F: Effect[F],
    http: HttpConfig
) {

  /**
    * Creates a new realm using the provided configuration.
    *
    * @param id           the realm id
    * @param name         the name of the realm
    * @param openIdConfig the address of the openid configuration
    * @param logo         an optional realm logo
    */
  def create(
      id: RealmLabel,
      name: String,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Caller): F[MetaOrRejection] = {
    val command = CreateRealm(id, name, openIdConfig, logo, caller.subject)
    check(write) >> openIdConfigAlreadyExistsOr(id, openIdConfig)(eval(command)) <* updateIndex(id)
  }

  /**
    * Updates an existing realm using the provided configuration.
    *
    * @param id           the realm id
    * @param rev          the current revision of the realm
    * @param name         the new name for the realm
    * @param openIdConfig the new openid configuration address
    * @param logo         an optional new logo
    */
  def update(
      id: RealmLabel,
      rev: Long,
      name: String,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Caller): F[MetaOrRejection] = {
    val command = UpdateRealm(id, rev, name, openIdConfig, logo, caller.subject)
    check(id, write) >> openIdConfigAlreadyExistsOr(id, openIdConfig)(eval(command)) <* updateIndex(id)
  }

  private def openIdConfigAlreadyExistsOr(id: RealmLabel, openIdConfig: Uri)(
      eval: => F[MetaOrRejection]
  )(implicit caller: Caller): F[MetaOrRejection] =
    list().flatMap {
      case realms if openIdConfigExists(id, openIdConfig, realms) =>
        F.pure(Left(RealmOpenIdConfigAlreadyExists(id, openIdConfig)))
      case _ => eval
    }

  private def openIdConfigExists(excludeLabel: RealmLabel, matchOpenIdConfig: Uri, resources: Seq[Resource]): Boolean =
    resources.exists(_.value match {
      case Left(realm)  => realm.id != excludeLabel && realm.openIdConfig == matchOpenIdConfig
      case Right(realm) => realm.id != excludeLabel && realm.openIdConfig == matchOpenIdConfig
    })

  /**
    * Deprecates an existing realm. A deprecated realm prevents clients from authenticating.
    *
    * @param id  the id of the realm
    * @param rev the revision of the realm
    */
  def deprecate(id: RealmLabel, rev: Long)(implicit caller: Caller): F[MetaOrRejection] =
    check(id, write) >> eval(DeprecateRealm(id, rev, caller.subject)) <* updateIndex(id)

  /**
    * Fetches a realm.
    *
    * @param id the id of the realm
    * @return the realm in a Resource representation, None otherwise
    */
  def fetch(id: RealmLabel)(implicit caller: Caller): F[OptResource] =
    check(id, read) >> fetchUnsafe(id)

  /**
    * Fetches a realm at a specific revision.
    *
    * @param id  the id of the realm
    * @param rev the revision of the realm
    * @return the realm in a Resource representation, None otherwise
    */
  def fetch(id: RealmLabel, rev: Long)(implicit caller: Caller): F[OptResource] =
    check(id, read) >> fetchUnsafe(id, Some(rev))

  /**
    * @param params filter parameters of the realms
    * @return the current realms sorted by their creation date.
    */
  def list(params: SearchParams = SearchParams.empty)(implicit caller: Caller): F[Seq[Resource]] =
    check(read) >> index.values.map(set => filter(set, params).toVector.sortBy(_.createdAt.toEpochMilli))

  private def filter(resources: Set[Resource], params: SearchParams): Set[Resource] =
    resources.filter {
      case ResourceF(_, rev, types, _, createdBy, _, updatedBy, value) =>
        params.createdBy.forall(_ == createdBy.id) &&
          params.updatedBy.forall(_ == updatedBy.id) &&
          params.rev.forall(_ == rev) &&
          params.types.subsetOf(types) &&
          params.deprecated.forall {
            case true  => value.isLeft
            case false => value.isRight
          }
    }

  /**
    * Attempts to compute the caller from the given [[AccessToken]].
    *
    * @param token the provided token
    * @return a caller reference if the token is valid, or an error in the F context otherwise
    */
  def caller(token: AccessToken): F[Caller] = {
    def jwt: Either[TokenRejection, SignedJWT] =
      Either
        .catchNonFatal(SignedJWT.parse(token.value))
        .leftMap(_ => InvalidAccessTokenFormat)
    def claims(jwt: SignedJWT): Either[TokenRejection, JWTClaimsSet] =
      Try(jwt.getJWTClaimsSet).filter(_ != null).toEither.leftMap(_ => InvalidAccessTokenFormat)
    def issuer(claimsSet: JWTClaimsSet): Either[TokenRejection, String] =
      Option(claimsSet.getIssuer).map(Right.apply).getOrElse(Left(AccessTokenDoesNotContainAnIssuer))
    def activeRealm(issuer: String): F[Either[TokenRejection, ActiveRealm]] =
      index.values
        .map {
          _.foldLeft(None: Option[ActiveRealm]) {
            case (s @ Some(_), _) => s
            case (acc, e)         => e.value.fold(_ => acc, ar => if (ar.issuer == issuer) Some(ar) else acc)
          }.toRight(UnknownAccessTokenIssuer)
        }
    def valid(jwt: SignedJWT, jwks: JWKSet): Either[TokenRejection, JWTClaimsSet] = {
      val proc        = new DefaultJWTProcessor[SecurityContext]
      val keySelector = new JWSVerificationKeySelector(JWSAlgorithm.RS256, new ImmutableJWKSet[SecurityContext](jwks))
      proc.setJWSKeySelector(keySelector)
      Either
        .catchNonFatal(proc.process(jwt, null))
        .leftMap(_ => TokenRejection.InvalidAccessToken)
    }
    @SuppressWarnings(Array("UnnecessaryConversion"))
    def caller(claimsSet: JWTClaimsSet, realm: ActiveRealm, exp: Option[Instant]): F[Either[TokenRejection, Caller]] = {
      val authenticated     = Authenticated(realm.id.value)
      val preferredUsername = Try(claimsSet.getStringClaim("preferred_username")).filter(_ != null).toOption
      val subject           = (preferredUsername orElse Option(claimsSet.getSubject)).toRight(AccessTokenDoesNotContainSubject)
      groups.groups(token, claimsSet, realm, exp).map { gs =>
        subject.map { sub =>
          val user = User(sub, realm.id.value)
          Caller(user, gs.toSet[Identity] + Anonymous + user + authenticated)
        }
      }
    }

    val triple: Either[TokenRejection, (SignedJWT, JWTClaimsSet, String)] = for {
      signed <- jwt
      cs     <- claims(signed)
      iss    <- issuer(cs)
    } yield (signed, cs, iss)

    val eitherCaller = for {
      t                 <- EitherT.fromEither[F](triple)
      (signed, cs, iss) = t
      realm             <- EitherT(activeRealm(iss))
      _                 <- EitherT.fromEither[F](valid(signed, realm.keySet))
      exp               = Try(signed.getJWTClaimsSet.getExpirationTime.toInstant).toOption
      result            <- EitherT(caller(cs, realm, exp))
    } yield result

    eitherCaller.value.flatMap {
      case Right(c) => F.pure(c)
      case Left(tr) => F.raiseError(ServiceError.InvalidAccessToken(tr))
    }
  }

  private def fetchUnsafe(id: RealmLabel, optRev: Option[Long] = None): F[OptResource] =
    optRev
      .map { rev =>
        agg
          .foldLeft[State](id.value, Initial) {
            case (state, event) if event.rev <= rev => next(state, event)
            case (state, _)                         => state
          }
          .map {
            case Initial if rev != 0L       => None
            case c: Current if rev != c.rev => None
            case other                      => other.optResource
          }
      }
      .getOrElse(agg.currentState(id.value).map(_.optResource))

  private def check(id: RealmLabel, permission: Permission)(implicit caller: Caller): F[Unit] =
    acls
      .flatMap(_.hasPermission(id.aclTarget, permission))
      .ifM(F.unit, F.raiseError(ServiceError.AccessDenied(id.toUri(http.realmsUri), permission)))

  private def check(permission: Permission)(implicit caller: Caller): F[Unit] =
    acls
      .flatMap(_.hasPermission(RootAcl, permission, ancestors = false))
      .ifM(F.unit, F.raiseError(ServiceError.AccessDenied(http.realmsUri, permission)))

  private def eval(cmd: Command): F[MetaOrRejection] =
    agg
      .evaluateS(cmd.id.value, cmd)
      .flatMap {
        case Left(rej) => F.pure(Left(rej))
        // $COVERAGE-OFF$
        case Right(Initial) => F.raiseError(ServiceError.UnexpectedInitialState(cmd.id.toUri(http.realmsUri)))
        // $COVERAGE-ON$
        case Right(c: Current) => F.pure(Right(c.resourceMetadata))
      }

  private[realms] def updateIndex(id: RealmLabel): F[Unit] =
    fetchUnsafe(id).flatMap {
      case Some(res) => index.put(id, res)
      case None      => F.unit
    }
}

object Realms {

  /**
    * Creates a new realm index.
    */
  def index[F[_]: Effect: Timer](implicit as: ActorSystem, rc: RealmsConfig): RealmIndex[F] = {
    implicit val cfg: KeyValueStoreConfig = rc.keyValueStore
    val clock: (Long, Resource) => Long   = (_, resource) => resource.rev
    KeyValueStore.distributed("realms", clock)
  }

  /**
    * Constructs a new realms aggregate.
    */
  def aggregate[F[_]: Effect: Timer: Clock](client: HttpClient[F])(
      implicit as: ActorSystem,
      rc: RealmsConfig
  ): F[Agg[F]] = {
    implicit val retryPolicy: RetryPolicy[F] = rc.aggregate.retry.retryPolicy[F]
    AkkaAggregate.sharded[F](
      "realms",
      RealmState.Initial,
      next,
      evaluate[F](client),
      rc.aggregate.passivationStrategy(),
      rc.aggregate.akkaAggregateConfig,
      rc.aggregate.shards
    )
  }

  /**
    * Creates a new realms api using the provided aggregate, a lazy reference to the ACL api and a realm index reference.
    *
    * @param agg    the permissions aggregate
    * @param acls   a lazy reference to the ACL api
    * @param index  a realm index reference
    * @param groups a groups provider reference
    */
  def apply[F[_]: Effect](
      agg: Agg[F],
      acls: F[Acls[F]],
      index: RealmIndex[F],
      groups: Groups[F]
  )(implicit http: HttpConfig): Realms[F] =
    new Realms(agg, acls, index, groups)

  /**
    * Creates a new permissions api using the default aggregate and a lazy reference to the ACL api.
    *
    * @param acls   a lazy reference to the ACL api
    * @param groups a groups provider reference
    * @param client an http client
    */
  def apply[F[_]: Effect: Timer: Clock](acls: F[Acls[F]], groups: Groups[F], client: HttpClient[F])(
      implicit
      as: ActorSystem,
      http: HttpConfig,
      rc: RealmsConfig
  ): F[Realms[F]] =
    delay(aggregate(client), acls, index, groups)

  /**
    * Creates a new realms api using the provided aggregate, a lazy reference to the ACL api and a realm index.
    *
    * @param agg    a lazy reference to the permissions aggregate
    * @param acls   a lazy reference to the ACL api
    * @param index  a realm index reference
    * @param groups a groups provider reference
    */
  def delay[F[_]: Effect](
      agg: F[Agg[F]],
      acls: F[Acls[F]],
      index: RealmIndex[F],
      groups: Groups[F]
  )(implicit http: HttpConfig): F[Realms[F]] =
    agg.map(apply(_, acls, index, groups))

  /**
    * Builds a process for automatically updating the realm index with the latest events logged.
    *
    * @param realms the realms API
    */
  def indexer[F[_]: Timer](realms: Realms[F])(implicit F: Effect[F], as: ActorSystem, rc: RealmsConfig): F[Unit] = {
    implicit val ac: AggregateConfig  = rc.aggregate
    implicit val ec: ExecutionContext = as.dispatcher
    implicit val tm: Timeout          = ac.askTimeout

    val projectionId = "realm-index"
    val source: Source[PairMsg[Any], _] = PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](rc.aggregate.queryJournalPlugin)
      .eventsByTag(TaggingAdapter.realmEventTag, NoOffset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

    val flow = ProgressFlowElem[F, Any]
      .collectCast[Event]
      .groupedWithin(rc.indexing.batch, rc.indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .mapAsync(ev => realms.fetchUnsafe(ev.id).map(_.map(ev.id -> _)))
      .collectSome[(RealmLabel, Resource)]
      .mapAsync { case (label, res) => index.put(label, res) }
      .flow
      .map(_ => ())

    F.delay[StreamSupervisor[F, Unit]](StreamSupervisor.startSingleton(F.delay(source.via(flow)), projectionId)) >> F.unit

  }

  private[realms] def next(state: State, event: Event): State = {
    // format: off
    def created(e: RealmCreated): State = state match {
      case Initial => Active(e.id, e.rev, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, e.instant, e.subject, e.instant, e.subject)
      case other   => other
    }
    def updated(e: RealmUpdated): State = state match {
      case s: Current => Active(e.id, e.rev, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, s.createdAt, s.createdBy, e.instant, e.subject)
      case other      => other
    }
    def deprecated(e: RealmDeprecated): State = state match {
      case s: Active => Deprecated(e.id, e.rev, s.name, s.openIdConfig, s.logo, s.createdAt, s.createdBy, e.instant, e.subject)
      case other     => other
    }
    // format: on
    event match {
      case e: RealmCreated    => created(e)
      case e: RealmUpdated    => updated(e)
      case e: RealmDeprecated => deprecated(e)
    }
  }

  private def evaluate[F[_]: Effect: Clock](client: HttpClient[F])(state: State, cmd: Command): F[EventOrRejection] = {
    val F = implicitly[Monad[F]]
    val C = implicitly[Clock[F]]
    def instantF: F[Instant] =
      C.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)
    def accept(f: Instant => Event): F[EventOrRejection] =
      instantF.map { instant => Right(f(instant)) }
    def reject(rejection: Rejection): F[EventOrRejection] =
      F.pure(Left(rejection))
    // format: off
    def create(c: CreateRealm): F[EventOrRejection] = state match {
      case Initial =>
        for {
          instant  <- instantF
          wkeither <- WellKnown(client, c.openIdConfig)
        } yield wkeither.map { wk =>
          RealmCreated(c.id, 1L, c.name, c.openIdConfig, wk.issuer, wk.keys, wk.grantTypes, c.logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, instant, c.subject)
        }
      case _ => reject(RealmAlreadyExists(c.id))
    }
    def update(c: UpdateRealm): F[EventOrRejection] = state match {
      case Initial                      => reject(RealmNotFound(c.id))
      case s: Current if s.rev != c.rev => reject(IncorrectRev(c.rev, s.rev))
      case s: Current =>
        for {
          instant  <- instantF
          wkeither <- WellKnown(client, c.openIdConfig)
        } yield wkeither.map { wk =>
          RealmUpdated(c.id, s.rev + 1, c.name, c.openIdConfig, wk.issuer, wk.keys, wk.grantTypes, c.logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, instant, c.subject)
        }
    }
    def deprecate(c: DeprecateRealm): F[EventOrRejection] = state match {
      case Initial                      => reject(RealmNotFound(c.id))
      case s: Current if s.rev != c.rev => reject(IncorrectRev(c.rev, s.rev))
      case _: Deprecated                => reject(RealmAlreadyDeprecated(c.id))
      case s: Current                   => accept(RealmDeprecated(s.id, s.rev + 1, _, c.subject))
    }
    // format: on

    cmd match {
      case c: CreateRealm    => create(c)
      case c: UpdateRealm    => update(c)
      case c: DeprecateRealm => deprecate(c)
    }
  }
}
