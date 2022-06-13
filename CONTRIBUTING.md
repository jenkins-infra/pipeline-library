# Contributing to Pipeline Global Library for ci.jenkins.io

👏 First off, thanks a lot for taking the time to contribute to this global library of pipeline components for [ci.jenkins.io](https://ci.jenkins.io/) (and many more Jenkins controllers of the [Jenkins Infrastructure Platform](https://www.jenkins.io/projects/infrastructure/)!

The following document contains all the resources you should need in order to get you started, from documents to read, to technical information allowing you to setup your environment. If you think this document is missing some information, feel free to contribute to it as well 😉

## Table of Contents

- [Contributing to Pipeline Global Library for ci.jenkins.io](#contributing-to-pipeline-global-library-for-cijenkinsio)
  - [Table of Contents](#table-of-contents)
  - [Code Of Conduct](#code-of-conduct)
  - [TL;DR: I'm lost and I just have a question!](#tldr-im-lost-and-i-just-have-a-question)
  - [What can I contribute?](#what-can-i-contribute)
  - [How to contribute?](#how-to-contribute)
  - [How will my contribution be evaluated?](#how-will-my-contribution-be-evaluated)
  - [How can I test my contribution?](#how-can-i-test-my-contribution)
  - [How to setup my environment?](#how-to-setup-my-environment)
    - [Signing Commits](#signing-commits)
    - [Technical Requirements](#technical-requirements)
  - [Tips and Tricks: Discovering the Project](#tips-and-tricks-discovering-the-project)
    - [Useful commands](#useful-commands)
    - [Finding the unit tests](#finding-the-unit-tests)
    - [Shared library documentation](#shared-library-documentation)
  - [Licensing information](#licensing-information)
  - [Styleguide](#styleguide)
    - [Git Commit Messages](#git-commit-messages)
    - [Groovy Style](#groovy-style)
  - [Links & Resources](#links--resources)

## Code Of Conduct

Jenkins has a [Code of Conduct](https://www.jenkins.io/project/conduct/) which applies to all contributors and to all components hosted by the project.

By participating,you are expected to uphold this code.

## TL;DR: I'm lost and I just have a question!

If you're completely lost, chances are you were searching for more global documentation about Jenkins contributions. You'll probably find your answer on the Jenkins [Participate and Contribute](https://www.jenkins.io/participate/) webpage.

If you're lost but wanted some details about the Jenkins infrastructure, then a good entry point might be the [Jenkins infrastructure documentation](https://github.com/jenkins-infra/documentation).

If you wanted to become a contributor for the whole Jenkins infrastructure project, you may want to refer to [that documentation](https://www.jenkins.io/projects/infrastructure/#contributing).

If you actually just had a question about the Pipeline Global Library, please don't create a Github issue for that (as they are used for keeping track of actions to do on the project) and prefer joining our [chat room](https://www.jenkins.io/chat/#jenkins-infra) or sending the team an [email](https://groups.google.com/u/1/g/jenkins-infra).

Please note that there is also this [newcomer Gitter channel](https://gitter.im/jenkinsci/newcomer-contributors) which could come in handy.

## What can I contribute?

We welcome any contribution whether it is documentation, bugfixes, new features, etc.

If you would like to contribute but you don't have an idea yet, we recommend you checking the [open issues](https://github.com/jenkins-infra/pipeline-library/issues) on the project. The ones with the *help-wanted* label are probably good starter points.

Otherwise, if you'd like to contribute a new idea, or if you'd like to report a bug, please start by creating a [github issue](https://github.com/jenkins-infra/pipeline-library/issues/new/choose) in order to initiate a discussion with the team. While creating an issue, remember that good questions to take into consideration are:

- Why (what is the problem to solve - high level value)?
- What (what your proposal to solve the problem)?
- How (what are the technical changes to do)?

Please don't hesitate to start your contribution journey by discussing with the team in order to ensure you're not taking time contributing something which has already been considered, or which isn't a good fit for the project.

## How to contribute?

This project uses [Github Flow](https://guides.github.com/introduction/flow/index.html), so all code changes happen through [Pull Requests](https://github.com/jenkins-infra/pipeline-library/pulls). In order to create a Pull Request, you have to:

- Fork the repo and create your branch from master.
- If you've added code that should be tested, add tests.
- If you've changed APIs, update the documentation.
- Ensure the test suite passes.
- Make sure your code lints.
- Issue that pull request!

## How will my contribution be evaluated?

As much as all contributions are welcome in the project, please keep in mind that any addition to the source code means some additional code to maintain. While a contribution usually is a one time effort, maintaining a consistent code base is a long running task.

Keeping that in mind, it makes sense to consider that a contribution needs to be evaluated by the team in charge of the repository, not only to consider the quality of what you produces, but also to evaluate if it's reasonable enough for the team to take some commitment on the long term regarding the addition you propose.

This is the reason why we strongly encourage you to start your contribution journey through discussion with the team, to ensure you are not consuming time and producing efforts which wouldn't lead to an actualy integration within the repository. By starting an early discussion, you'll most likely avoid seeing your contribution rejected.

Once your contribution is made though, please keep in mind that the team will need to review your proposal, and might have some questions or even requests for modifications. Please follow the discussions and keep the Pull Request alive for a smooth experience.

## How can I test my contribution?

This project contains some automated tests which are located in the [test folder](https://github.com/jenkins-infra/pipeline-library/tree/master/test/groovy).

They can be executed with [Maven](https://maven.apache.org/) using the following command: `mvn test`.

If you are contributing new features or changes to the code base, please consider implementing some automated tests as well.

## How to setup my environment?

### Signing Commits

Your commits need to be signed and verified by Github. You can achieve this by following [this documentation](https://docs.github.com/en/authentication/managing-commit-signature-verification/signing-commits).

*Don't forget to add your GPG key to your Github profile*.

### Technical Requirements

In order to work with that repository you will need:

- OpenJDK 8: https://openjdk.java.net/projects/jdk8/
- Maven 3.6+: https://maven.apache.org/download.cgi
- Groovy: https://groovy-lang.org/install.html

An IDE or text editor of your choice, like [Vim](https://www.vim.org/), [Emacs](https://www.gnu.org/software/emacs/) or [VS Code](https://code.visualstudio.com/) for example.

### Add a `pre-push` git hook to check the lint before pushing

By adding the following file `pre-push` (without extension) into your local git repository `.git/hooks` folder, the command `mvn spotless:chek` will run before every push you'll make to check if your code is still correctly linted, and will prevent pushing malformatted code which would fail your build anyway.

```bash
#!/bin/sh

mvn spotless:check
```
If your push has been aborted, you can manually fix the reported errors, or use the following command to automatically fix them: `mvn spotless:apply`

Notes:
- this hook is totally optional.
- this hook works for Linux and macOS, you'll need to adapt it for Windows.
- if you don't mind spending several seconds on every commit, you can put this as a `pre-commit` hook.
- you can make this script automatically fix the errors by changing `:check` by `:apply` in the hook.

## Tips and Tricks: Discovering the Project

Here are some useful tips and tricks allowing you to discover a bit more about the project and get to know some handy commands.

### Useful commands

Here are some helpful commands you may find useful:

- `mvn test`: executing the automated tests of the project
- `mvn compile`: compiling the source code

### Finding the unit tests

The unit tests of the project are located in that [test folder](https://github.com/jenkins-infra/pipeline-library/tree/master/test/groovy). After executing the tests, you'll find the tests results in the `target/` folder at the root of the repository.

### Shared library documentation

As per now, all the documentation related to the pipeline library is located in this [README file](https://github.com/jenkins-infra/pipeline-library/blob/master/README.adoc).

## Licensing information

Any contributions you make will be under the MIT Software License

In short, when you submit code changes, your submissions are understood to be under the same [MIT License](http://choosealicense.com/licenses/mit/) that covers the project. Feel free to contact the maintainers if that's a concern.

## Styleguide

### Git Commit Messages

- Use the present tense ("Add feature" not "Added feature")
- Use the imperative mood ("Move cursor to..." not "Moves cursor to...")
- Limit the first line to 72 characters or less
- Reference issues and pull requests liberally after the first line

### Groovy Style

This project uses the [Spotless Maven plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven) to manage the code formatting.
The enforced code formatting rules are defined in the file `src/spotless/greclipse.properties` derived from <https://github.com/google/styleguide/blob/gh-pages/eclipse-java-google-style.xml>.

As a contributor, you can:

- Format the code locally by executing the command line `mvn spotless:apply` and/or setting up your text editor to format with the code formatting rules.
- Verify that the code is correctly formatted by running the command line `mvn spotless:check`.

Please note that the continuous integration process executes the goal `spotless:check` as part of the `verify` step (as per the pipeline definition in `Jenkinsfile`).

## Links & Resources

- [Jenkins Infrastructure Contributions](https://www.jenkins.io/projects/infrastructure/#contributing)
