# The deployment-owned backup bucket and the one identity that may write it.
# Rendered only with redis-storage-managed: true. The access key is a
# sensitive output read back by the package; nothing here carries a secret.
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "6.31.0" }
  }
}
provider "aws" { region = "us-east-1" }
locals {
  buckets = {
    backup = "redis-aws-fixture-backup"
  }
  tags = { "colors:profile" = "redis-aws-fixture", "colors:owner" = "redis-storage" }
}
resource "aws_s3_bucket" "application" {
  for_each = local.buckets
  bucket = each.value
  force_destroy = true
  lifecycle { prevent_destroy = true }
  tags = local.tags
}
resource "aws_s3_bucket_public_access_block" "application" {
  for_each = aws_s3_bucket.application
  bucket = each.value.id
  block_public_acls = true
  block_public_policy = true
  ignore_public_acls = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_server_side_encryption_configuration" "application" {
  for_each = aws_s3_bucket.application
  bucket = each.value.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
# One identity per bucket, scoped to that bucket alone: the pair that reaches
# the host can write backup sets and nothing else in the account.
resource "aws_iam_user" "application" {
  for_each = local.buckets
  name = "redis-aws-fixture-storage-${each.key}"
  tags = local.tags
}
resource "aws_iam_user_policy" "application" {
  for_each = aws_iam_user.application
  name = "redis-bucket"
  user = each.value.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads"], Resource = [aws_s3_bucket.application[each.key].arn] },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"], Resource = ["${aws_s3_bucket.application[each.key].arn}/*"] }
    ]
  })
}
resource "aws_iam_access_key" "application" {
  for_each = aws_iam_user.application
  user = each.value.name
  depends_on = [aws_iam_user_policy.application]
}
output "credentials" {
  value = { for role, key in aws_iam_access_key.application : role => { access_key_id = key.id, secret_access_key = key.secret } }
  sensitive = true
}
