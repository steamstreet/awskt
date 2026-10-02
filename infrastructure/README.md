# Infrastructure

## The Steamstreet Maven repository

`package-repository.yaml` is the CloudFormation template of the stack
`SteamStreetDevelopmentInfrastructure`, in the Steamstreet account (141660060409), `us-west-2`. It
was created in 2020 with only the bucket; the template here is now its source.

- **Bucket** `steamstreet-repository`, private. Artifacts live under `maven/release/`.
- **Read:** `https://repo.steamstreet.com`, a CloudFront distribution that serves `maven/release`
  read-only to anyone, without credentials. The libraries are open source. CloudFront reads the
  bucket through an Origin Access Control, so the bucket's public-access block stays on and nothing
  outside `maven/release` is served. A missing path answers 404, so Gradle moves on to its next
  repository.
- **Caching:** released artifacts never change and are cached for a day or more.
  `maven-metadata.xml` is rewritten by every release and is cached for at most five minutes.
- **Write:** the IAM user `steamstreet-maven-publisher`, which can only read, write and list the
  bucket's `maven/` tree. `scripts/release.sh` publishes as this user.
- **Domain:** `repo.steamstreet.com` is an alias record in the `steamstreet.com` zone, on the
  account's `*.steamstreet.com` certificate in `us-east-1`. CloudFront accepts only `us-east-1`
  certificates and this stack is in `us-west-2`, so the certificate's ARN is a parameter.

### Setting up the publishing profile

The release script reads the publisher's access key from the local AWS profile
`steamstreet-publisher`, or the one named by `AWSKT_PUBLISH_PROFILE`. It is a static key rather than
an SSO profile so that a release never waits on a login, and because Gradle's S3 support cannot use
an SSO profile itself. The key is created by hand, outside the stack, so its secret never appears in
CloudFormation. With an administrator session on the Steamstreet account:

```bash
aws iam create-access-key --user-name steamstreet-maven-publisher --profile steamstreet
aws configure --profile steamstreet-publisher   # paste the key id and secret; region us-west-2
```

Check it with `aws sts get-caller-identity --profile steamstreet-publisher`, which should name the
user `steamstreet-maven-publisher`. To rotate, create a second key, reconfigure the profile, and
delete the first with `aws iam delete-access-key`.

### Changing the stack

```bash
aws cloudformation create-change-set --profile steamstreet --region us-west-2 \
  --stack-name SteamStreetDevelopmentInfrastructure --change-set-name <name> \
  --template-body file://infrastructure/package-repository.yaml \
  --parameters ParameterKey=PackageRepositoryName,UsePreviousValue=true \
  --capabilities CAPABILITY_NAMED_IAM
```

Review it with `describe-change-set`, then `execute-change-set`. Keep the bucket's logical id,
`PackageRepository`, unchanged: renaming it would replace the bucket.
