package my.pipeline.lib

class MailExtSender {

    final def script
    String subject = "Undefined"
    String body = "Undefined"
    List<String> recipients = []
    String attachmentsPattern = null

    MailExtSender(def script) {
        this.script = script
    }

    def withSubject(String subject) {
        this.subject = subject
        return this
    }

    def withRecipient(String email) {
        this.recipients << email
        return this
    }

    def withTestReports(boolean add) {
        this.attachmentsPattern = add ? "target/**/TEST-*.xml" : null
        return this
    }

    void send() {
        script.emailext subject: subject, body: body,
                        attachmentsPattern: attachmentsPattern,
            to: recipients.join(',')
    }
}

