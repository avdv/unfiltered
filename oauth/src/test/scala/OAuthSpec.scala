package unfiltered.oauth

import org.specs2.mutable._

object OAuthSpec extends Specification with unfiltered.specs2.jetty.Served {

  import unfiltered.response._
  import unfiltered.request._
  import unfiltered.request.{Path => UFPath}
  import dispatch.classic._
  import dispatch.classic.oauth._
  import OAuth._

  System.setProperty("file.encoding", "UTF-8")
  val consumer = Consumer("key", "secret")

  def setup = { server =>

    trait CustomPaths extends OAuthPaths {
      override val RequestTokenPath = "/requests"
      override val AuthorizationPath = "/auth"
      override val AccessTokenPath = "/access"
    }

    val stores = new MockOAuthStores {
      var tokenMap = scala.collection.mutable.Map.empty[String, unfiltered.oauth.Token]

      override val consumers = new ConsumerStore {
        def get(key: String) = Some(new unfiltered.oauth.Consumer {
          val key = consumer.key
          val secret = consumer.secret
        })
      }

      override val tokens = new DefaultTokenStore {
        override def get(key: String) = tokenMap.get(key)
        override def put(token: unfiltered.oauth.Token) = {
          tokenMap += (token.key -> token)
          token
        }
        override def delete(key: String) = tokenMap -= key
      }
    }

    server.context("/oauth") {
      _.filter(new OAuth(stores) with CustomPaths)
    }
    .filter(Protection(stores))
    .filter(unfiltered.filter.Planify {
      case request =>
        ResponseString(request.underlying.getAttribute(unfiltered.oauth.OAuth.XAuthorizedIdentity) match {
          case null => "unknown user. abort! abort!"
          case id: String => id
        })
    })
  }

  "oauth" should {
    "authorize a valid consumer's request using a HMAC-SHA1 signature with oob workflow" in {
      val payload = Map("identité" -> "caché",
        "identi+y" -> "hidden+",
        "アイデンティティー" -> "秘密",
        "pita" -> "-._~*+%20")
      val request_token = http(host.POST / "oauth" / "requests" <<? payload << Map("rand" -> scala.util.Random.nextString(1024)) <@(consumer, OAuth.oob) as_token)
      val VerifierRE = """<p id="verifier">(.+)</p>""".r
      val verifier = http(host / "oauth" / "auth" with_token request_token as_str) match {
        case VerifierRE(v) => v
        case _ => "?"
      }
      val access_token = http(host.POST / "oauth" / "access" <@ (consumer, request_token, verifier) as_token)
      val user = http(host / "user" <@(consumer, access_token, verifier) as_str)
      user must_== "test_user"
    }
  }
}
