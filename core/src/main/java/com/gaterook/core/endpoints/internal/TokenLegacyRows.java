package com.gaterook.core.endpoints.internal;

import com.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import com.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import com.gaterook.core.endpoints.internal.persistence.TokenKind;
import com.gaterook.core.platform.LegacyRows;
import org.springframework.stereotype.Component;

/**
 * Named tokens issued before {@code api_token} had a MAC (V86): signed once by the upgrade, so the clients that hold
 * them keep working. Bridge tokens are not: they live 15 minutes, and a bridge whose token is refused asks for a new
 * one.
 */
@Component
class TokenLegacyRows implements LegacyRows {

  private final ApiTokenRepository tokens;
  private final TokenRows rows;

  TokenLegacyRows(ApiTokenRepository tokens, TokenRows rows) {
    this.tokens = tokens;
    this.rows = rows;
  }

  @Override
  public String migration() {
    return "86";
  }

  @Override
  public String signLegacyRows() {
    int signed = 0;
    for (ApiTokenEntity t : tokens.findByKindAndMacIsNull(TokenKind.NAMED)) {
      rows.sign(t);
      tokens.save(t);
      signed++;
    }
    return signed == 0 ? null : signed + (signed == 1 ? " access token" : " access tokens");
  }
}
