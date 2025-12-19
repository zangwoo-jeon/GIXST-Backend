package com.AISA.AISA.portfolio.PortfolioGroup;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.portfolio.PortfolioGroup.dto.PortfolioCreateRequest;
import com.AISA.AISA.portfolio.PortfolioGroup.dto.PortfolioNameUpdateRequest;
import com.AISA.AISA.portfolio.PortfolioGroup.exception.PortfolioErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.AISA.AISA.portfolio.PortfolioGroup.dto.PortfolioResponse;

import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

import com.AISA.AISA.member.adapter.in.Member;
import com.AISA.AISA.member.adapter.in.MemberRepository;
import com.AISA.AISA.member.adapter.in.exception.MemberErrorCode;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioService {
    private final PortfolioRepository portfolioRepository;
    private final MemberRepository memberRepository;

    public List<PortfolioResponse> findPortfolios(String username) {
        if (username == null) {
            return portfolioRepository.findAll().stream()
                    .map(PortfolioResponse::new)
                    .collect(toList());
        }
        Member member = getMemberByUsername(username);
        return portfolioRepository.findByMemberId(member.getMemberId()).stream()
                .map(PortfolioResponse::new)
                .collect(toList());
    }

    @Transactional
    public Portfolio createPortfolio(String username, PortfolioCreateRequest request) {
        Member member = getMemberByUsername(username);
        boolean isFirstPortfolio = portfolioRepository.findByMemberId(member.getMemberId()).isEmpty();
        Portfolio newPortfolio = new Portfolio(member.getMemberId(), request.getPortName());

        if (isFirstPortfolio) {
            newPortfolio.designateAsMain();
        }

        return portfolioRepository.save(newPortfolio);
    }

    @Transactional
    public void deletePortfolio(String username, UUID portId) {
        Member member = getMemberByUsername(username);
        Portfolio portfolioToDelete = portfolioRepository.findByPortIdAndMemberId(portId, member.getMemberId())
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

        boolean wasMain = portfolioToDelete.isMainPort();

        portfolioRepository.delete(portfolioToDelete);

        if (wasMain) {
            portfolioRepository.findByMemberId(member.getMemberId()).stream()
                    .findFirst()
                    .ifPresent(Portfolio::designateAsMain);
        }

    }

    @Transactional
    public void updatePortfolioName(String username, UUID portId, PortfolioNameUpdateRequest request) {
        Member member = getMemberByUsername(username);
        Portfolio portfolio = portfolioRepository.findByPortIdAndMemberId(portId, member.getMemberId())
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

        portfolio.changeName(request.getNewPortName());
    }

    @Transactional
    public void changeMainPortfolio(String username, UUID portId) {
        Member member = getMemberByUsername(username);
        List<Portfolio> portfolios = portfolioRepository.findByMemberId(member.getMemberId());

        if (portfolios.isEmpty()) {
            throw new BusinessException(PortfolioErrorCode.MEMBER_HAS_NO_PORTFOLIOS);
        }

        portfolios.forEach(p -> {
            if (p.getPortId().equals(portId))
                p.designateAsMain();
            else
                p.unDesignateAsMain();
        });
    }

    private Member getMemberByUsername(String username) {
        return memberRepository.findByUserName(username)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
