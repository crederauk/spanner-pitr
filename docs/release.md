## Releasing

This project uses the following procedures and tools to create releases:
* The [`reckon`][Reckon] library is used to determine an appropriate version identifier for the application based on the state of the repository, taking into account tags, branches, uncommitted changes and other factors.
* The `reckon` Gradle plugin is used to set the `project.version` in a Gradle build for all other plugins that use it. This includes `maven-publish` and `jib`.
* To release, a developer will use `reckon` to create a new tag and push it.
* When a new tag is pushed to the repository GitHub Actions will execute the workflow described in `.github/workflows/tag.yml`:
    1. Build the application JAR and Docker image (using [Jib])
    1. Push both the JAR and Docker image to GitHub Packages. The Docker image will have multiple tags, including `latest` and its version identifier.
    1. Create a [GitHub Release].
    1. Create a [GitHub deployment].
    1. Deploy the application to all environments.

There are two types of release – _final_ and _hotfix_.

A final release is created from a tag on the `master` branch. This is the normal way to create a release.

A hotfix release is created from a branch taken from a tag. A hotfix release should be created if an **urgent** fix need to be made to the version currently running in production, and `master` contains changes that cannot be deployed to production. For a project with frequent normal deployments to production and the use of [feature toggles], this should rarely be required.

If you are unfamiliar with Semantic Versioning, please read https://semver.org before creating your first release.

### Creating a final release
1. Ensure your local `HEAD` points to `master`, and that `master` is up-to-date with `origin/master`.
1. Determine whether this is a "major", "minor" or "patch" release based on the changes made since the last release. Replace `SCOPE` in the following commands with either `major`, `minor` or `patch` as appropriate.
1. Check that the determined version makes sense by looking at the output of:
    ```
    $ ./gradlew build -Preckon.stage=final -Preckon.scope=SCOPE --dry-run
    ```
1. Create a Git tag to establish the new version and push it:
    ```
    $ ./gradlew reckonTagPush -Preckon.stage=final -Preckon.scope=SCOPE
    ```

### Creating a hotfix release
1. Create and checkout a new branch from the tag of the version currently running in production. For example, for tag `4.2.0`:
    ```
    $ git checkout -b 4.2.0-hotfix 4.2.0
    ```
1. Make the necessary changes and commit them to this hotfix branch.
1. Push the branch and its changes:
    ```
    $ git push --set-upstream origin 4.2.0-hotfix
    ```
1. Create and push a new tag with stage "hotfix" and "patch" scope:
    ```
    ./gradlew reckonTagPush -Preckon.stage=hotfix -Preckon.scope=patch
    ```
   In this case, the version identifier assigned will be `4.2.1-hotfix.1`. If your next planned final release is going to be `4.2.1` then `4.2.1-hotfix.1` might seem a confusing choice. It might help to think of it as _a hotfix release, which is a patch on `4.2.0`, in preparation for `4.2.1`_.
1. After the changes have been deployed, if the issue is not resolved and you need to make further changes, repeat the steps above to commit, push, push a new tag. In the example above, the next tag would be `4.2.1-hotfix.2`.
1. Apply the commits from the hotfix branch to the `master` branch:
    ```
    $ git checkout master
    $ git cherry-pick -x <commit>
    ```
   Where `<commit>` is the commit on the hotfix branch.


[Spring Boot]: https://spring.io/projects/spring-boot#learn
[Jib]: https://github.com/GoogleContainerTools/jib
[Reckon]: https://github.com/ajoberstar/reckon
[GitHub Release]: https://help.github.com/en/github/administering-a-repository/about-releases
[GitHub deployment]: https://help.github.com/en/github/administering-a-repository/viewing-deployment-activity-for-your-repository
[feature toggles]: https://martinfowler.com/articles/feature-toggles.html