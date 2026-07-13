# Elysium AI Gateway

The gateway is the only Elysium component that talks to the OpenAI Responses API. The Android APK never contains an OpenAI API key: it stores a separately scoped gateway token with Android Keystore protection.

## Start locally

1. Create a local environment file from `.env.example` outside version control.
2. Set a freshly rotated `OPENAI_API_KEY` and a long random `ELY_GATEWAY_TOKEN` in that local environment.
3. Start the service with Node 20 or newer:

   ```sh
   npm start
   ```

4. Configure the Android Command Core with the gateway URL and the `ELY_GATEWAY_TOKEN` value. Do not put an OpenAI key in the app.

For a locally running gateway on a development Mac, forward the Android device's localhost endpoint with ADB before opening Command Core:

```sh
adb -s <device-serial> reverse tcp:8787 tcp:8787
```

The APK default `http://localhost:8787` is intentionally restricted to that ADB path. Any non-local gateway URL must use HTTPS.

The default listener is `127.0.0.1:8787`. For an Android phone on the same network during development, expose it deliberately through TLS or a private tunnel; production deployments must use HTTPS and an authenticated reverse proxy.

## Contract

- `GET /healthz` reports a dependency-free health response.
- `POST /v1/agent/turn` starts a Responses API turn.
- `POST /v1/agent/continue` submits bounded structured output from an approved tool call.

The model can request exactly twelve typed tools. Every mutation is returned to Android as `requiresApproval: true`; there is no arbitrary shell-command tool. Tool execution stays local to the APK and every request has a bounded payload, rate limit, timeout, opaque safety identifier, and redacted audit event.

Run the gateway test suite with:

```sh
npm test
```
