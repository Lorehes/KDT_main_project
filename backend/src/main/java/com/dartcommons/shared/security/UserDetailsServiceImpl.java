package com.dartcommons.shared.security;

import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/*
 * [목적] Spring Security DaoAuthenticationProvider용 UserDetailsService — 이메일 로그인 시 DB 조회.
 *       loadUserByUsername의 파라미터는 email (로그인 폼 username 필드에 email 사용).
 * [이유] 이메일/비밀번호 로그인 시 AuthService가 직접 UserRepository를 조회하므로
 *       이 빈은 SecurityConfig의 DaoAuthenticationProvider에 등록되지 않음.
 *       JwtAuthenticationFilter는 이 서비스를 직접 호출하지 않고 JWT 클레임에서 userId를 추출.
 *       → 현재 이 구현은 향후 Spring Security form login 경로 추가 시를 위한 확장 포인트.
 * [사이드 임팩트] soft delete된 사용자는 UsernameNotFoundException — 탈퇴 후 재로그인 차단.
 * [수정 시 고려사항] 권한(Granted Authority)은 "ROLE_{TIER}" — SecurityConfig requestMatchers와 일치해야 함.
 *                  authorities 확장 시(예: ADMIN 역할) UserEntity에 별도 role 필드 추가 필요.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserEntity user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        return User.builder()
                .username(user.getId().toString())
                .password(user.getPasswordHash() != null ? user.getPasswordHash() : "")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getTier().name())))
                .build();
    }
}
