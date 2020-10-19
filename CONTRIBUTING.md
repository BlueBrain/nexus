# Contributing to the Blue Brain Nexus Platform

We would love for you to contribute to the Blue Brain Nexus Platform and help make it even better than it is today! As a
contributor, here are the guidelines we would like you to follow:
 - [Question or Problem?](#question)
 - [Issues and Bugs](#issue)
 - [Feature Requests](#feature)
 - [Submission Guidelines](#submit)
 - [Signing the CLA](#cla)
 
## <a name="question"></a> Got a Question or Problem?

Please do not hesitate to contact the [Blue Brain Nexus team by email][nexus-team-email].

## <a name="issue"></a> Found a Bug?

If you find a bug in the source code, you can help us by [submitting an issue](#submit-issue) to our
[GitHub Repository][github]. Even better, you can [submit a Pull Request](#submit-pr) with a fix.

## <a name="feature"></a> Missing a Feature?

You can *request* a new feature by [submitting an issue](#submit-issue) to our GitHub Repository. If you would like to
*implement* a new feature, please submit an issue with a proposal for your work first, to be sure that we can use it.

Please consider what kind of change it is:
* For a **Major Feature**, first open an issue and outline your proposal so that it can be
discussed. This will also allow us to better coordinate our efforts, prevent duplication of work,
and help you to craft the change so that it is successfully accepted into the project.
* **Small Features** can be crafted and directly [submitted as a Pull Request](#submit-pr).

## <a name="submit"></a> Submission Guidelines

### <a name="submit-issue"></a> Submitting an Issue

Before you submit an issue, please search the issue tracker, maybe an issue for your problem already exists and the
discussion might inform you of workarounds readily available.

We want to fix all the issues as soon as possible, but before fixing a bug we need to reproduce and confirm it. In order
to reproduce bugs we will need as much information as possible, and preferably be in touch with you to gather
information.

### <a name="submit-pr"></a> Submitting a Pull Request (PR)

When you wish to contribute to the code base, please consider the following guidelines:
* Make a [fork](https://guides.github.com/activities/forking/) of this repository.
* Make your changes in your fork, in a new git branch:
     ```shell
     git checkout -b my-fix-branch master
     ```
* Create your patch, **including appropriate test cases**.
* Run the full test suite, and ensure that all tests pass.
* Commit your changes using a descriptive commit message.
     ```shell
     git commit -a
     ```
  Note: the optional commit `-a` command line option will automatically “add” and “rm” edited files.
* Push your branch to GitHub:
    ```shell
    git push origin my-fix-branch
    ```
* Please sign our [Contributor License Agreement (CLA)](#cla) before sending Pull Requests (PR).
  We cannot accept any contribution without your signature.
* In GitHub, send a Pull Request to the `master` branch of the upstream repository of the relevant component.
* If we suggest changes then:
  * Make the required updates.
  * Re-run the test suites to ensure tests are still passing.
  * Rebase your branch and force push to your GitHub repository (this will update your Pull Request):
    ```shell
    git rebase master -i
    git push -f
    ```
That’s it! Thank you for your contribution!

#### After your pull request is merged

After your pull request is merged, you can safely delete your branch and pull the changes from the main (upstream)
repository:
* Delete the remote branch on GitHub either through the GitHub web UI or your local shell as follows:
    ```shell
    git push origin --delete my-fix-branch
    ```
* Check out the master branch:
    ```shell
    git checkout master -f
    ```
* Delete the local branch:
    ```shell
    git branch -D my-fix-branch
    ```
* Update your master with the latest upstream version:
    ```shell
    git pull --ff upstream master
    ```
## <a name="cla"></a> Signing the CLA

If you are not part of the Blue Brain project, a Contributor License Agreement (CLA) must be signed for any code changes
to be accepted. Please contact the [Blue Brain Nexus team][nexus-team-email] to get the latest CLA version and
instructions.

[nexus-team-email]: mailto:bbp-nexus-support@groupes.epfl.ch
[github]: https://github.com/BlueBrain/nexus
