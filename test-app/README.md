# Sample Fly application

Copied from https://github.com/fly-apps/hello-static, this is a simple app for gitea-fly-connector testing.

## Deploying

First, create a repository in Gitea. The settings don't necessarily matter, but I recommend making it Private
so we can test authenticated SSH reads. Also, don't check off the "Initialize Repository" option.

Add gitea-fly-connector webhook settings to the repository, as described in the GFC README.

Then, in the same directory as this README.md, run:

```bash

# create repository
git init .
git remote add origin GITEA_REPOSITORY_URL

# add initial files
git add .
git commit -m "initial commit!"

# prep the app for deployment (needs a unique app name)
fly launch --no-deploy
git add fly.toml
git commit -m "launch app"

# rename master -> main if necessary, then push
git branch -m main
git push -u origin main
```