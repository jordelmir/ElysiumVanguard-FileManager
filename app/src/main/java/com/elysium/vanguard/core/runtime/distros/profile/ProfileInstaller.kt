package com.elysium.vanguard.core.runtime.distros.profile

import com.elysium.vanguard.core.runtime.distros.DistroFamily
import java.io.File
import java.nio.file.Files

/**
 * Phase 12.3 — materialize a [ElysiumProfile] as a sequence of
 * install steps the distro's provisioning pipeline can execute.
 *
 * Master order §11.4: a profile is a pair of
 *   - the upstream package list (installed via apt-get / apk / pacman)
 *   - the Elysium SystemLayer (applied via the layer updater)
 *
 * This class is the *planner* — it builds the inputs the installer
 * uses, but does not execute them. The execution is the caller's
 * job (the installer runs the command in the staging chroot, then
 * hands the SystemLayer tarball to the layer updater). Splitting
 * planning from execution keeps the planner JVM-testable end-to-end
 * without needing a real package manager.
 */
class ProfileInstaller {

    /**
     * Build the install plan for [profile] running on a guest of
     * [family]. The plan contains the shell command to invoke and
     * a description of the SystemLayer that follows.
     *
     * Note: the plan's [Plan.layerTarballPlaceholder] is a real
     * (empty) file — it satisfies [com.elysium.vanguard.core.runtime.distros.layer.SystemLayer]'s
     * init validation. The caller MUST replace it with the actual
     * downloaded layer tarball and re-compute the SHA-256 before
     * handing the SystemLayer to the updater. The placeholder's
     * SHA-256 is intentionally all-zeros, so a forgetful caller
     * fails the hash check loudly rather than applying a bogus
     * layer.
     */
    fun plan(profile: ElysiumProfile, family: DistroFamily): Plan {
        val command = installCommand(profile, family)
        val placeholderTarball = Files.createTempFile(
            "elysium-profile-${profile.id}-placeholder",
            ".tar.gz"
        ).toFile()
        return Plan(
            profile = profile,
            family = family,
            installCommand = command,
            layerId = profile.layerId,
            layerDisplayName = profile.displayName,
            layerVersion = profile.layerVersion,
            layerTarballPlaceholder = placeholderTarball
        )
    }

    /**
     * Build the install command string for a profile on a given
     * distro family. The command is the *first* step of a profile
     * install: the upstream packages go in, then the SystemLayer
     * overlay.
     */
    fun installCommand(profile: ElysiumProfile, family: DistroFamily): String {
        if (profile.packages.isEmpty()) {
            // Headless: no packages to install. The layer still
            // applies (it carries the Elysium CLI shim, branding
            // files, etc.) but there's no package-manager call.
            return "# no upstream packages for ${profile.id}"
        }
        val names = profile.packages.joinToString(" ")
        return when (family) {
            DistroFamily.DEBIAN -> "DEBIAN_FRONTEND=noninteractive apt-get install -y $names"
            DistroFamily.MUSL -> "apk add --no-cache $names"
            DistroFamily.ARCH -> "pacman -Syu --noconfirm $names"
        }
    }

    /**
     * Immutable install plan. Carries the upstream package
     * command and the metadata of the SystemLayer that follows
     * it. Callers render the [installCommand] to the user, then
     * execute it in the staging chroot, then download the layer
     * tarball (replacing [layerTarballPlaceholder] with a real
     * file), then hand the resulting SystemLayer to the layer
     * updater.
     */
    data class Plan(
        val profile: ElysiumProfile,
        val family: DistroFamily,
        val installCommand: String,
        val layerId: String,
        val layerDisplayName: String,
        val layerVersion: String,
        /**
         * Real but empty file that satisfies the SystemLayer init
         * check. Callers MUST replace this with the actual
         * downloaded layer tarball before constructing the
         * SystemLayer.
         */
        val layerTarballPlaceholder: File
    )
}
