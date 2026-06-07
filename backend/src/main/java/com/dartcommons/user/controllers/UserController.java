package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.UpdateMeRequest;
import com.dartcommons.user.dto.UserMeResponse;
import com.dartcommons.user.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 사용자 프로필 REST 엔드포인트 — GET/PATCH/DELETE /api/v1/users/me.
 * [이유] "me" 패턴으로 본인 리소스만 접근 — 별도 userId 경로 파라미터 불필요, IDOR 위험 제거.
 *       JWT 필터가 principal에 userId(Long)를 설정 → @AuthenticationPrincipal로 주입.
 * [사이드 임팩트] DELETE /me → soft delete + 전 기기 refresh token 삭제. access token은 만료(30분) 대기.
 * [수정 시 고려사항] PATCH에 nickname 외 필드 추가 시 UpdateMeRequest 확장.
 *                  탈퇴 전 확인 코드(이메일 재인증 등)가 필요하면 2-step 흐름 추가.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserMeResponse getMe(@AuthenticationPrincipal Long userId) {
        return userService.getMe(userId);
    }

    @PatchMapping("/me")
    public UserMeResponse updateMe(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody UpdateMeRequest request) {
        return userService.updateMe(userId, request);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal Long userId) {
        userService.softDeleteMe(userId);
    }
}
