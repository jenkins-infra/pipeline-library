import my.pipeline.lib.MailExtSender

node('linux') {
    stage("Checkout") {
        demo.checkout()
    }

    stage("Notify") {
        def builder = new MailExtSender(this)
            .withSubject("Test results for project Foo")
            .withRecipient("oleg-nenashev@foo.bar")
            .withTestReports(true)
            .send()
    }

}
