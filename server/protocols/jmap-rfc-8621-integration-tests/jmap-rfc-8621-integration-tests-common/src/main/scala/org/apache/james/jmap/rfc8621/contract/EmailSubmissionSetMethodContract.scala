/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.rfc8621.contract

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import io.netty.handler.codec.http.HttpHeaderNames.ACCEPT
import io.restassured.RestAssured.{`given`, requestSpecification}
import io.restassured.builder.ResponseSpecBuilder
import io.restassured.http.ContentType.JSON
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.http.HttpStatus.SC_OK
import org.apache.james.GuiceJamesServer
import org.apache.james.jmap.http.UserCredential
import org.apache.james.jmap.rfc8621.contract.Fixture.{ACCEPT_RFC8621_VERSION_HEADER, ACCOUNT_ID, ANDRE, ANDRE_PASSWORD, BOB, BOB_PASSWORD, DOMAIN, ANDRE_ACCOUNT_ID, authScheme, baseRequestSpecBuilder}
import org.apache.james.mailbox.DefaultMailboxes
import org.apache.james.mailbox.MessageManager.AppendCommand
import org.apache.james.mailbox.model.MailboxACL.Right
import org.apache.james.mailbox.model.{MailboxACL, MailboxId, MailboxPath, MessageId}
import org.apache.james.mime4j.dom.Message
import org.apache.james.modules.{ACLProbeImpl, MailboxProbeImpl}
import org.apache.james.utils.DataProbeImpl
import org.awaitility.Awaitility
import org.awaitility.Duration.ONE_HUNDRED_MILLISECONDS
import org.junit.jupiter.api.{BeforeEach, Test}

trait EmailSubmissionSetMethodContract {
  private lazy val slowPacedPollInterval = ONE_HUNDRED_MILLISECONDS
  private lazy val calmlyAwait = Awaitility.`with`
    .pollInterval(slowPacedPollInterval)
    .and.`with`.pollDelay(slowPacedPollInterval)
    .await
  private lazy val awaitAtMostTenSeconds = calmlyAwait.atMost(10, TimeUnit.SECONDS)

  @BeforeEach
  def setUp(server: GuiceJamesServer): Unit = {
    server.getProbe(classOf[DataProbeImpl])
      .fluent
      .addDomain(DOMAIN.asString)
      .addUser(BOB.asString, BOB_PASSWORD)
      .addUser(ANDRE.asString, ANDRE_PASSWORD)

    requestSpecification = baseRequestSpecBuilder(server)
      .setAuth(authScheme(UserCredential(BOB, BOB_PASSWORD)))
      .build
  }

  def randomMessageId: MessageId

  @Test
  def emailSubmissionSetCreateShouldSendMailSuccessfully(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val andreInboxPath = MailboxPath.inbox(ANDRE)
    val andreInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(andreInboxPath)

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestAndre =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ANDRE_ACCOUNT_ID",
         |      "filter": {"inMailbox": "${andreInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response = `given`(
        baseRequestSpecBuilder(server)
          .setAuth(authScheme(UserCredential(ANDRE, ANDRE_PASSWORD)))
          .addHeader(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
          .setBody(requestAndre)
          .build, new ResponseSpecBuilder().build)
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(response)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def emailSubmissionSetCreateShouldSendMailSuccessfullyToSelf(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val bobInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${BOB.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMail =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMail)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(response)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def mimeSenderShouldAcceptAliases(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender("bob.alias@domain.tld")
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    server.getProbe(classOf[DataProbeImpl]).addUserAliasMapping("bob.alias", DOMAIN.asString(), "bob@domain.tld")

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val bobInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${BOB.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMail =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMail)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(response)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def mimeFromShouldAcceptAliases(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom("bob.alias@domain.tld")
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    server.getProbe(classOf[DataProbeImpl]).addUserAliasMapping("bob.alias", DOMAIN.asString(), "bob@domain.tld")

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val bobInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${BOB.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMail =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMail)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(response)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def envelopeFromShouldAcceptAliases(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    server.getProbe(classOf[DataProbeImpl]).addUserAliasMapping("bob.alias", DOMAIN.asString(), "bob@domain.tld")

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val bobInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "bob.alias@domain.tld"},
         |             "rcptTo": [{"email": "${BOB.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMail =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMail)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(response)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def emailSubmissionSetCreateShouldSendMailSuccessfullyToBothRecipients(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val bobInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    val andreInboxPath = MailboxPath.inbox(ANDRE)
    val andreInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(andreInboxPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId


    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${BOB.asString}"}, {"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMailBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    val requestReadMailAndre =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ANDRE_ACCOUNT_ID",
         |      "filter": {"inMailbox": "${andreInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val responseBob = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMailBob)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString
      val responseAndre = `given`(
        baseRequestSpecBuilder(server)
          .setAuth(authScheme(UserCredential(ANDRE, ANDRE_PASSWORD)))
          .addHeader(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
          .setBody(requestReadMailAndre)
          .build, new ResponseSpecBuilder().build)
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(responseBob)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
      assertThatJson(responseAndre)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def emailSubmissionSetCanBeChainedAfterEmailSet(server: GuiceJamesServer): Unit = {
    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    val draftId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val bobInboxId: MailboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |    ["Email/set", {
         |      "accountId": "$ACCOUNT_ID",
         |      "create": {
         |        "e1526":{
         |          "mailboxIds": {"${draftId.serialize}": true},
         |          "to": [{"email": "${BOB.asString}"}],
         |          "from": [{"email": "${BOB.asString}"}]
         |        }
         |      }
         |    }, "c1"],
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "#e1526",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${BOB.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMailBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val responseBob = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMailBob)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(responseBob)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def emailSubmissionSetCreateShouldReturnSuccess(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      // Ids are randomly generated, and not stored, let's ignore it
      .whenIgnoringPaths("methodResponses[0][1].created.k1490")
      .inPath("methodResponses[0][1].created")
      .isEqualTo("""{"k1490": {}}""")
  }

  @Test
  def onSuccessUpdateEmailShouldTriggerAnImplicitEmailSetCall(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |       },
         |       "onSuccessUpdateEmail": {
         |         "${messageId.serialize}": {
         |           "keywords": {"$$sent":true}
         |         }
         |       }
         |   }, "c1"],
         |   ["Email/get",
         |     {
         |       "accountId": "$ACCOUNT_ID",
         |       "ids": ["${messageId.serialize}"],
         |       "properties": ["keywords"]
         |     },
         |     "c2"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      // Ids are randomly generated, and not stored, let's ignore it
      .whenIgnoringPaths("methodResponses[0][1].created.k1490")
      .isEqualTo(s"""{
                   |    "sessionState": "75128aab4b1b",
                   |    "methodResponses": [
                   |        [
                   |            "EmailSubmission/set",
                   |            {
                   |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                   |                "newState": "000001",
                   |                "created": {
                   |                    "k1490": "f0850507-bb63-4675-b14f-d560f8dca21f"
                   |                }
                   |            },
                   |            "c1"
                   |        ],
                   |        [
                   |            "Email/set",
                   |            {
                   |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                   |                "newState": "000001",
                   |                "updated": {
                   |                    "${messageId.serialize}": null
                   |                }
                   |            },
                   |            "c1"
                   |        ],
                   |        [
                   |            "Email/get",
                   |            {
                   |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                   |                "state": "000001",
                   |                "list": [
                   |                    {
                   |                        "keywords": {"$$sent": true},
                   |                        "id": "${messageId.serialize}"
                   |                    }
                   |                ],
                   |                "notFound": []
                   |            },
                   |            "c2"
                   |        ]
                   |    ]
                   |}""".stripMargin)
  }

  @Test
  def onSuccessDestroyEmailShouldTriggerAnImplicitEmailSetCall(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |       },
         |       "onSuccessDestroyEmail": ["${messageId.serialize}"]
         |   }, "c1"],
         |   ["Email/get",
         |     {
         |       "accountId": "$ACCOUNT_ID",
         |       "ids": ["${messageId.serialize}"],
         |       "properties": ["keywords"]
         |     },
         |     "c2"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      // Ids are randomly generated, and not stored, let's ignore it
      .whenIgnoringPaths("methodResponses[0][1].created.k1490")
      .isEqualTo(s"""{
                   |    "sessionState": "75128aab4b1b",
                   |    "methodResponses": [
                   |        [
                   |            "EmailSubmission/set",
                   |            {
                   |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                   |                "newState": "000001",
                   |                "created": {
                   |                    "k1490": "f0850507-bb63-4675-b14f-d560f8dca21f"
                   |                }
                   |            },
                   |            "c1"
                   |        ],
                   |        [
                   |            "Email/set",
                   |            {
                   |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                   |                "newState": "000001",
                   |                "destroyed": ["${messageId.serialize}"]
                   |            },
                   |            "c1"
                   |        ],
                   |        [
                   |            "Email/get",
                   |            {
                   |                "accountId": "29883977c13473ae7cb7678ef767cbfbaffc8a44a6e463d971d23a65c1dc4af6",
                   |                "state": "000001",
                   |                "list":[],
                   |                "notFound": ["${messageId.serialize}"]
                   |            },
                   |            "c2"
                   |        ]
                   |    ]
                   |}""".stripMargin)
  }

  @Test
  def setShouldRejectOtherAccountIds(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ANDRE_ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0]")
      .isEqualTo("""[
                   |  "error",
                   |  {"type": "accountNotFound"},
                   |  "c1"
                   |]""".stripMargin)
  }

  @Test
  def setShouldRejectMessageNotFound(): Unit = {
    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${randomMessageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "invalidArguments",
                   |    "description": "The email to be sent cannot be found",
                   |    "properties": ["emailId"]
                   |  }
                   |}""".stripMargin)
  }

  @Test
  def setShouldRejectExtraProperties(): Unit = {
    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${randomMessageId.serialize}",
         |           "extra": true,
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "invalidArguments",
                   |    "description": "Some unknown properties were specified",
                   |    "properties": ["extra"]
                   |  }
                   |}""".stripMargin)
  }

  @Test
  def setShouldRejectMessageOfOtherUsers(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val andreDraftsPath = MailboxPath.forUser(ANDRE, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(andreDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(ANDRE.asString(), andreDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "invalidArguments",
                   |    "description": "The email to be sent cannot be found",
                   |    "properties": ["emailId"]
                   |  }
                   |}""".stripMargin)
  }

  @Test
  def setShouldAcceptDelegatedMessages(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(BOB.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val andreDraftsPath = MailboxPath.forUser(ANDRE, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(andreDraftsPath)
    val bobInboxId = server.getProbe(classOf[MailboxProbeImpl]).createMailbox(MailboxPath.inbox(BOB))
    server.getProbe(classOf[ACLProbeImpl])
      .replaceRights(andreDraftsPath, BOB.asString, new MailboxACL.Rfc4314Rights(Right.Lookup, Right.Read))
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(ANDRE.asString(), andreDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${BOB.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)

    val requestReadMail =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],
         |  "methodCalls": [[
         |    "Email/query",
         |    {
         |      "accountId": "$ACCOUNT_ID",
         |      "filter": {"inMailbox": "${bobInboxId.serialize}"}
         |    },
         |    "c1"]]
         |}""".stripMargin

    awaitAtMostTenSeconds.untilAsserted { () =>
      val response = `given`
        .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
        .body(requestReadMail)
      .when
        .post
      .`then`
        .statusCode(SC_OK)
        .contentType(JSON)
        .extract
        .body
        .asString

      assertThatJson(response)
        .inPath("methodResponses[0][1].ids")
        .isArray
        .hasSize(1)
    }
  }

  @Test
  def setShouldRejectOtherUserUsageInSenderMimeField(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(ANDRE.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "forbiddenMailFrom",
                   |    "description": "Attempt to send a mail whose MimeMessage From and Sender fields not allowed for connected user: List(andre@domain.tld)"
                   |  }
                   |}""".stripMargin)
  }

  @Test
  def setShouldRejectOtherUserUsageInFromMimeField(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString, ANDRE.asString())
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "forbiddenMailFrom",
                   |    "description": "Attempt to send a mail whose MimeMessage From and Sender fields not allowed for connected user: List(andre@domain.tld)"
                   |  }
                   |}""".stripMargin)
  }

  @Test
  def setShouldRejectOtherUserUsageInFromEnvelopeField(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${ANDRE.asString}"},
         |             "rcptTo": [{"email": "${ANDRE.asString}"}]
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "forbiddenFrom",
                   |    "description": "Attempt to send a mail whose envelope From not allowed for connected user: andre@domain.tld",
                   |    "properties":["envelope.mailFrom"]
                   |  }
                   |}""".stripMargin)
  }

  @Test
  def setShouldRejectNoRecipients(server: GuiceJamesServer): Unit = {
    val message: Message = Message.Builder
      .of
      .setSubject("test")
      .setSender(BOB.asString)
      .setFrom(BOB.asString)
      .setTo(ANDRE.asString)
      .setBody("testmail", StandardCharsets.UTF_8)
      .build

    val bobDraftsPath = MailboxPath.forUser(BOB, DefaultMailboxes.DRAFTS)
    server.getProbe(classOf[MailboxProbeImpl]).createMailbox(bobDraftsPath)
    val messageId: MessageId = server.getProbe(classOf[MailboxProbeImpl]).appendMessage(BOB.asString(), bobDraftsPath, AppendCommand.builder()
      .build(message))
      .getMessageId

    val requestBob =
      s"""{
         |  "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"],
         |  "methodCalls": [
         |     ["EmailSubmission/set", {
         |       "accountId": "$ACCOUNT_ID",
         |       "create": {
         |         "k1490": {
         |           "emailId": "${messageId.serialize}",
         |           "envelope": {
         |             "mailFrom": {"email": "${BOB.asString}"},
         |             "rcptTo": []
         |           }
         |         }
         |    }
         |  }, "c1"]]
         |}""".stripMargin

    val response = `given`
      .header(ACCEPT.toString, ACCEPT_RFC8621_VERSION_HEADER)
      .body(requestBob)
    .when
      .post
    .`then`
      .statusCode(SC_OK)
      .contentType(JSON)
      .extract
      .body
      .asString

    assertThatJson(response)
      .inPath("methodResponses[0][1].notCreated")
      .isEqualTo("""{
                   |  "k1490": {
                   |    "type": "noRecipients",
                   |    "description": "Attempt to send a mail with no recipients"
                   |  }
                   |}""".stripMargin)
  }
}
