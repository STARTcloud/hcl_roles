## Description

Brief description of the changes in this pull request.

## Type of Change

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New role or feature (non-breaking change which adds functionality)
- [ ] Breaking change (variable renames, removed roles, changed behavior)
- [ ] Documentation update
- [ ] CI/workflow change

## Role Convention Checklist

- [ ] `ansible-lint` passes with zero failures and zero warnings (production profile, strict)
- [ ] Tasks use FQCN modules, gerund names, quoted modes, `changed_when` on commands
- [ ] `meta/argument_specs.yml` and the role README updated for any variable or behavior change
- [ ] No secrets in tracked files — real values in the gitignored root `vault.yml`, dummies in the repo
- [ ] Checked `startcloud.startcloud_roles` for overlap; subsumption noted in the role README where relevant
- [ ] Commit messages follow Conventional Commits (release-please depends on them)

## Testing performed

Describe how the change was verified (target OS, provisioner or playbook used, relevant output):

## Additional Context

Any additional information that reviewers should know:
