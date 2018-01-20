node("linux") {
    demo.checkout() // Wrapper for the demo checkout
    sh "mvn clean verify"
}
