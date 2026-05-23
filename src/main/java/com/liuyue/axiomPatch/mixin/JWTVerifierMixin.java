package com.liuyue.axiomPatch.mixin;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Claim;
import com.liuyue.axiomPatch.AxiomPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Proxy;
import java.util.Arrays;

@Mixin(targets = "com.auth0.jwt.JWTVerifier", remap = false)
public class JWTVerifierMixin {

    @SuppressWarnings("all")
    @Inject(method = "verify(Ljava/lang/String;)Lcom/auth0/jwt/interfaces/DecodedJWT;",
            at = @At("HEAD"), cancellable = true)
    private void onVerify(String token, CallbackInfoReturnable<Object> cir) {

        boolean isAxiom = Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(e -> e.getClassName().startsWith("com.moulberry"));

        if (!isAxiom) return;

        AxiomPatch.LOGGER.info("[Axiom-Patch] 成功拦截 Axiom 商业验证");

        try {
            ClassLoader loader = DecodedJWT.class.getClassLoader();

            Object fakeClaim = Proxy.newProxyInstance(loader, new Class<?>[]{Claim.class}, (p, method, args) -> switch (method.getName()) {
                case "asBoolean" -> Boolean.TRUE;
                case "isNull" -> Boolean.FALSE;
                case "asString" -> "true";
                case "isMissing" -> Boolean.FALSE;
                default -> null;
            });

            Object fakeDecodedJwt = Proxy.newProxyInstance(loader, new Class<?>[]{DecodedJWT.class}, (p, method, args) -> switch (method.getName()) {
                    case "getSubject" -> "00000000-0000-0000-0000-000000000000";
                    case "getClaim" -> fakeClaim;
                    case "getClaims" -> java.util.Collections.singletonMap("commercial", fakeClaim);
                    default -> null;
            });

            cir.setReturnValue(fakeDecodedJwt);

        } catch (Throwable t) {
            AxiomPatch.LOGGER.error("[Axiom-Patch] 伪造失败", t);
        }
    }
}